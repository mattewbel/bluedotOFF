// Minimal loopback TLS/ADB cryptographic bridge. No shell API, hostname or external destination.
#include <jni.h>
#include <openssl/ssl.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/bn.h>
#include <openssl/mem.h>
#include <openssl/rand.h>
#include <adb/pairing/pairing_auth.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <atomic>
#include <mutex>
#include <map>
#include <memory>
#include <vector>
#include <string>
#include <stdexcept>
#include <algorithm>

using Bytes=std::vector<uint8_t>;
static void require(bool v,const char* error){if(!v)throw std::runtime_error(error);}
static void clear(Bytes& b){if(!b.empty())OPENSSL_cleanse(b.data(),b.size());}
static Bytes input(JNIEnv* e,jbyteArray a){require(a!=nullptr,"Missing local ADB material");int n=e->GetArrayLength(a);require(n>=0&&n<=1048576,"Local ADB material size invalid");Bytes b(n);e->GetByteArrayRegion(a,0,n,reinterpret_cast<jbyte*>(b.data()));return b;}
static jbyteArray output(JNIEnv* e,const Bytes& b){auto a=e->NewByteArray(b.size());if(a)e->SetByteArrayRegion(a,0,b.size(),reinterpret_cast<const jbyte*>(b.data()));return a;}
static void error(JNIEnv* e,const char* msg){e->ThrowNew(e->FindClass("java/io/IOException"),msg);}
struct Socket {
    int fd=-1;
    bssl::UniquePtr<SSL_CTX> ctx;
    bssl::UniquePtr<SSL> ssl;
    std::mutex io;
    ~Socket(){if(fd>=0)::close(fd);}
    void read(uint8_t* b,size_t n){while(n){int r=ssl?SSL_read(ssl.get(),b,std::min(n,size_t(1048576))):recv(fd,b,n,0);require(r>0,"Local ADB read closed or timed out");b+=r;n-=r;}}
    void write(const uint8_t* b,size_t n){while(n){int r=ssl?SSL_write(ssl.get(),b,std::min(n,size_t(1048576))):send(fd,b,n,MSG_NOSIGNAL);require(r>0,"Local ADB write closed or timed out");b+=r;n-=r;}}
    void tls(){require(!ssl,"Local ADB TLS already active");ssl.reset(SSL_new(ctx.get()));require(ssl&&SSL_set_fd(ssl.get(),fd),"Local ADB TLS setup failed");SSL_set_connect_state(ssl.get());require(SSL_connect(ssl.get())==1,"Local ADB TLS authentication failed");}
};
static std::mutex registryLock;
static std::map<int64_t,std::shared_ptr<Socket>> registry;
static std::atomic<int64_t> nextId{1};
static std::shared_ptr<Socket> socketFor(jlong id){std::lock_guard<std::mutex> l(registryLock);auto it=registry.find(id);require(it!=registry.end(),"Local ADB session closed");return it->second;}
static void configure(Socket& s,const Bytes& key,const Bytes& cert){
    auto kb=bssl::UniquePtr<BIO>(BIO_new_mem_buf(key.data(),key.size()));
    auto cb=bssl::UniquePtr<BIO>(BIO_new_mem_buf(cert.data(),cert.size()));
    auto p=bssl::UniquePtr<EVP_PKEY>(PEM_read_bio_PrivateKey(kb.get(),nullptr,nullptr,nullptr));
    auto c=bssl::UniquePtr<X509>(PEM_read_bio_X509(cb.get(),nullptr,nullptr,nullptr));
    require(p&&c,"Stored ADB identity invalid");
    s.ctx.reset(SSL_CTX_new(TLS_method()));require(bool(s.ctx),"Local ADB TLS unavailable");
    require(SSL_CTX_set_min_proto_version(s.ctx.get(),TLS1_3_VERSION)&&SSL_CTX_set_max_proto_version(s.ctx.get(),TLS1_3_VERSION),"Local ADB TLS version unavailable");
    require(SSL_CTX_use_certificate(s.ctx.get(),c.get())&&SSL_CTX_use_PrivateKey(s.ctx.get(),p.get())&&SSL_CTX_check_private_key(s.ctx.get()),"ADB identity mismatch");
    // AOSP's pairing trust is SPAKE2 bound to the TLS exporter. Normal ADB also verifies
    // the client identity on adbd; only loopback is accepted and helper identity is checked.
    SSL_CTX_set_verify(s.ctx.get(),SSL_VERIFY_PEER|SSL_VERIFY_FAIL_IF_NO_PEER_CERT,nullptr);
    SSL_CTX_set_cert_verify_callback(s.ctx.get(),[](X509_STORE_CTX*,void*){return 1;},nullptr);
}
static void append32(Bytes& b,uint32_t v){for(int i=0;i<4;i++)b.push_back(v>>(i*8));}
static Bytes pem(BIO* bio){char* p=nullptr;long n=BIO_get_mem_data(bio,&p);require(n>0,"ADB identity encoding failed");return Bytes(p,p+n);}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_bluedotoff_adb_NativeAdb_identity(JNIEnv* e,jobject){
    Bytes privatePem;
    try{
        auto rsa=bssl::UniquePtr<RSA>(RSA_new());auto exponent=bssl::UniquePtr<BIGNUM>(BN_new());
        require(rsa&&exponent&&BN_set_word(exponent.get(),65537)&&RSA_generate_key_ex(rsa.get(),2048,exponent.get(),nullptr),"ADB key generation failed");
        auto key=bssl::UniquePtr<EVP_PKEY>(EVP_PKEY_new());require(key&&EVP_PKEY_set1_RSA(key.get(),rsa.get()),"ADB key initialization failed");
        auto cert=bssl::UniquePtr<X509>(X509_new());require(bool(cert),"ADB certificate unavailable");
        require(X509_set_version(cert.get(),2)&&ASN1_INTEGER_set(X509_get_serialNumber(cert.get()),1)&&X509_gmtime_adj(X509_get_notBefore(cert.get()),-60)&&X509_gmtime_adj(X509_get_notAfter(cert.get()),315360000)&&X509_set_pubkey(cert.get(),key.get()),"ADB certificate setup failed");
        auto name=X509_get_subject_name(cert.get());require(X509_NAME_add_entry_by_txt(name,"CN",MBSTRING_ASC,reinterpret_cast<const uint8_t*>("bluedotOFF"),-1,-1,0)&&X509_set_issuer_name(cert.get(),name)&&X509_sign(cert.get(),key.get(),EVP_sha256()),"ADB certificate signing failed");
        auto kb=bssl::UniquePtr<BIO>(BIO_new(BIO_s_mem()));auto cb=bssl::UniquePtr<BIO>(BIO_new(BIO_s_mem()));
        require(kb&&cb&&PEM_write_bio_PrivateKey(kb.get(),key.get(),nullptr,nullptr,0,nullptr,nullptr)&&PEM_write_bio_X509(cb.get(),cert.get()),"ADB identity serialization failed");
        privatePem=pem(kb.get());auto certPem=pem(cb.get());
        char* secret=nullptr;long secretSize=BIO_get_mem_data(kb.get(),&secret);OPENSSL_cleanse(secret,secretSize);
        const BIGNUM* n=nullptr;const BIGNUM* publicE=nullptr;RSA_get0_key(rsa.get(),&n,&publicE,nullptr);
        Bytes adbKey;append32(adbKey,64);
        uint8_t modulus[256];require(BN_bn2le_padded(modulus,256,n),"ADB modulus encoding failed");
        uint32_t first=uint32_t(modulus[0])|(uint32_t(modulus[1])<<8)|(uint32_t(modulus[2])<<16)|(uint32_t(modulus[3])<<24);
        uint32_t inverse=first;for(int i=0;i<5;i++)inverse*=2-first*inverse;append32(adbKey,0-inverse);
        adbKey.insert(adbKey.end(),modulus,modulus+256);
        auto rr=bssl::UniquePtr<BIGNUM>(BN_new());auto power=bssl::UniquePtr<BIGNUM>(BN_new());auto bn=bssl::UniquePtr<BN_CTX>(BN_CTX_new());
        require(rr&&power&&bn&&BN_set_bit(power.get(),4096)&&BN_mod(rr.get(),power.get(),n,bn.get())&&BN_bn2le_padded(modulus,256,rr.get()),"ADB key reduction failed");
        adbKey.insert(adbKey.end(),modulus,modulus+256);append32(adbKey,BN_get_word(publicE));
        Bytes b64(4*((adbKey.size()+2)/3)+1);int len=EVP_EncodeBlock(b64.data(),adbKey.data(),adbKey.size());require(len>0,"ADB public encoding failed");b64.resize(len);
        const std::string comment=" bluedotOFF";b64.insert(b64.end(),comment.begin(),comment.end());b64.push_back(0);
        Bytes result;for(auto* p:{&privatePem,&certPem,&b64}){append32(result,p->size());result.insert(result.end(),p->begin(),p->end());}
        auto out=output(e,result);clear(privatePem);clear(result);return out;
    }catch(const std::exception& ex){clear(privatePem);error(e,ex.what());return nullptr;}
}
extern "C" JNIEXPORT jlong JNICALL Java_app_bluedotoff_adb_NativeAdb_open(JNIEnv* e,jobject,jint port,jbyteArray keyArray,jbyteArray certArray){
    Bytes key;
    try{
        require(port>0&&port<=65535,"Invalid local ADB port");key=input(e,keyArray);auto cert=input(e,certArray);
        auto s=std::make_shared<Socket>();configure(*s,key,cert);clear(key);
        s->fd=::socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);require(s->fd>=0,"Local ADB socket unavailable");
        int noDelay=1;require(setsockopt(s->fd,IPPROTO_TCP,TCP_NODELAY,&noDelay,sizeof(noDelay))==0,"Local ADB low-latency socket setup failed");
        int flags=fcntl(s->fd,F_GETFL,0);require(flags>=0&&fcntl(s->fd,F_SETFL,flags|O_NONBLOCK)==0,"Local socket setup failed");
        sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_port=htons(port);addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        int r=connect(s->fd,reinterpret_cast<sockaddr*>(&addr),sizeof(addr));
        if(r!=0){require(errno==EINPROGRESS,"Local ADB connection unavailable");pollfd p{s->fd,POLLOUT,0};require(poll(&p,1,3000)>0,"Local ADB connect timeout");int err=0;socklen_t n=sizeof(err);require(getsockopt(s->fd,SOL_SOCKET,SO_ERROR,&err,&n)==0&&err==0,"Local ADB connection unavailable");}
        require(fcntl(s->fd,F_SETFL,flags)==0,"Local socket reset failed");timeval t{8,0};setsockopt(s->fd,SOL_SOCKET,SO_RCVTIMEO,&t,sizeof(t));setsockopt(s->fd,SOL_SOCKET,SO_SNDTIMEO,&t,sizeof(t));
        auto id=nextId++;{std::lock_guard<std::mutex> l(registryLock);registry.emplace(id,s);}return id;
    }catch(const std::exception& ex){clear(key);error(e,ex.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_app_bluedotoff_adb_NativeAdb_startTls(JNIEnv* e,jobject,jlong id){try{auto s=socketFor(id);std::lock_guard<std::mutex> l(s->io);s->tls();}catch(const std::exception& ex){error(e,ex.what());}}
extern "C" JNIEXPORT void JNICALL Java_app_bluedotoff_adb_NativeAdb_write(JNIEnv* e,jobject,jlong id,jbyteArray data){Bytes b;try{b=input(e,data);auto s=socketFor(id);std::lock_guard<std::mutex> l(s->io);s->write(b.data(),b.size());clear(b);}catch(const std::exception& ex){clear(b);error(e,ex.what());}}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_bluedotoff_adb_NativeAdb_read(JNIEnv* e,jobject,jlong id,jint length){try{require(length>=0&&length<=1048576,"Local ADB response size invalid");auto s=socketFor(id);std::lock_guard<std::mutex> l(s->io);Bytes b(length);s->read(b.data(),b.size());return output(e,b);}catch(const std::exception& ex){error(e,ex.what());return nullptr;}}
extern "C" JNIEXPORT void JNICALL Java_app_bluedotoff_adb_NativeAdb_close(JNIEnv*,jobject,jlong id){std::shared_ptr<Socket> s;{std::lock_guard<std::mutex> l(registryLock);auto it=registry.find(id);if(it==registry.end())return;s=it->second;registry.erase(it);}shutdown(s->fd,SHUT_RDWR);}
extern "C" JNIEXPORT jstring JNICALL Java_app_bluedotoff_adb_NativeAdb_pair(JNIEnv* e,jobject,jlong id,jbyteArray codeArray,jbyteArray publicArray){
    Bytes password;
    try{
        password=input(e,codeArray);require(password.size()==6&&std::all_of(password.begin(),password.end(),[](uint8_t c){return c>='0'&&c<='9';}),"Pairing code must be six digits");
        auto pub=input(e,publicArray);require(pub.size()>1&&pub.size()<8192&&pub.back()==0,"ADB public identity invalid");
        auto s=socketFor(id);std::lock_guard<std::mutex> l(s->io);s->tls();
        uint8_t exported[64];const char label[]="adb-label";require(SSL_export_keying_material(s->ssl.get(),exported,sizeof(exported),label,sizeof(label),nullptr,0,0),"Pairing channel binding failed");password.insert(password.end(),exported,exported+64);OPENSSL_cleanse(exported,sizeof(exported));
        auto auth=std::unique_ptr<PairingAuthCtx,decltype(&pairing_auth_destroy)>(pairing_auth_client_new(password.data(),password.size()),pairing_auth_destroy);clear(password);require(bool(auth),"Pairing crypto unavailable");
        auto sendPacket=[&](uint8_t type,const Bytes& b){require(!b.empty()&&b.size()<=16384,"Pairing packet size invalid");uint8_t h[6]={1,type, uint8_t(b.size()>>24),uint8_t(b.size()>>16),uint8_t(b.size()>>8),uint8_t(b.size())};s->write(h,6);s->write(b.data(),b.size());};
        auto receive=[&](uint8_t type){uint8_t h[6];s->read(h,6);uint32_t n=(uint32_t(h[2])<<24)|(uint32_t(h[3])<<16)|(uint32_t(h[4])<<8)|h[5];require(h[0]==1&&h[1]==type&&n>0&&n<=16384,"Pairing protocol invalid");Bytes b(n);s->read(b.data(),n);return b;};
        Bytes msg(pairing_auth_msg_size(auth.get()));pairing_auth_get_spake2_msg(auth.get(),msg.data());sendPacket(0,msg);auto peer=receive(0);require(pairing_auth_init_cipher(auth.get(),peer.data(),peer.size()),"Pairing authentication failed");
        Bytes info(8192,0);std::copy(pub.begin(),pub.end(),info.begin()+1);Bytes encrypted(pairing_auth_safe_encrypted_size(auth.get(),info.size()));size_t size=0;require(pairing_auth_encrypt(auth.get(),info.data(),info.size(),encrypted.data(),&size),"Pairing encryption failed");encrypted.resize(size);sendPacket(1,encrypted);
        auto reply=receive(1);Bytes plain(pairing_auth_safe_decrypted_size(auth.get(),reply.data(),reply.size()));require(pairing_auth_decrypt(auth.get(),reply.data(),reply.size(),plain.data(),&size)&&size==8192&&plain[0]==1,"Pairing failed or code expired");
        auto end=std::find(plain.begin()+1,plain.end(),0);require(end!=plain.end()&&end!=plain.begin()+1,"Pairing peer identity invalid");std::string guid(plain.begin()+1,end);require(guid.size()<=128&&std::all_of(guid.begin(),guid.end(),[](char c){return (c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_';}),"Pairing peer identity invalid");clear(plain);return e->NewStringUTF(guid.c_str());
    }catch(const std::exception& ex){clear(password);error(e,ex.what());return nullptr;}
}
