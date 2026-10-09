package app.bluedotoff.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Bounded, read-only DEX verifier. No class loading, reflection or code execution. */
public final class DexAbi {
    public static final class Result {
        public final Map<String,String> methods = new TreeMap<>();
        public final Map<String,Integer> transactions = new TreeMap<>();
    }
    private final byte[] bytes; private final int header;
    private final int stringsAt,stringsCount,typesAt,typesCount,protosAt,protosCount,fieldsAt,fieldsCount,methodsAt,methodsCount;
    private final Map<Integer,String> strings = new HashMap<>();
    private static final int[] WIDTH = new int[256];
    static {
        Arrays.fill(WIDTH,1);
        int[] two={2,5,8,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,0x29,0xfe,0xff}; for(int op:two)WIDTH[op]=2;
        int[] three={3,6,9,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c,0xfc,0xfd}; for(int op:three)WIDTH[op]=3;
        WIDTH[0x18]=5; WIDTH[0xfa]=WIDTH[0xfb]=4;
        int[][] ranges={{0x2d,0x3d},{0x44,0x6d},{0x90,0xaf},{0xd0,0xe2}}; for(int[] range:ranges)for(int op=range[0];op<=range[1];op++)WIDTH[op]=2;
        for(int op=0x6e;op<=0x72;op++)WIDTH[op]=3; for(int op=0x74;op<=0x78;op++)WIDTH[op]=3;
    }
    private DexAbi(byte[] bytes,int h) throws IOException {
        this.bytes=bytes; header=h;
        need(h,112);
        if(bytes[h]!='d'||bytes[h+1]!='e'||bytes[h+2]!='x'||bytes[h+3]!='\n')throw new IOException("Not DEX");
        String version=new String(bytes,h+4,3,StandardCharsets.US_ASCII);
        if(!Arrays.asList("035","037","038","039","040","041").contains(version))throw new IOException("Unknown DEX version");
        if(u32(h+40)!=0x12345678)throw new IOException("Unsupported DEX byte order");
        int hs=u32(h+36);if(hs!=112&&hs!=120)throw new IOException("Unknown header");
        stringsCount=u32(h+56);stringsAt=u32(h+60);typesCount=u32(h+64);typesAt=u32(h+68);protosCount=u32(h+72);protosAt=u32(h+76);
        fieldsCount=u32(h+80);fieldsAt=u32(h+84);methodsCount=u32(h+88);methodsAt=u32(h+92);
        need(stringsAt,Math.multiplyExact(stringsCount,4));need(typesAt,Math.multiplyExact(typesCount,4));need(protosAt,Math.multiplyExact(protosCount,12));need(fieldsAt,Math.multiplyExact(fieldsCount,8));need(methodsAt,Math.multiplyExact(methodsCount,8));
    }
    private void need(int at,int count)throws IOException {if(at<0||count<0||at>bytes.length-count)throw new IOException("DEX boundary");}
    private int u16(int at)throws IOException {need(at,2);return (bytes[at]&255)|((bytes[at+1]&255)<<8);}
    private int u32(int at)throws IOException {need(at,4);return (bytes[at]&255)|((bytes[at+1]&255)<<8)|((bytes[at+2]&255)<<16)|((bytes[at+3]&255)<<24);}
    private int leb(int[] at)throws IOException {int n=0;for(int i=0;i<5;i++){need(at[0],1);int b=bytes[at[0]++]&255;if(i==4&&(b&0xf0)!=0)throw new IOException("ULEB overflow");n|=(b&127)<<(7*i);if(b<128)return n;}throw new IOException("ULEB overflow");}
    private int signedLeb(int[] at)throws IOException {int n=0,shift=0,b;do{need(at[0],1);b=bytes[at[0]++]&255;n|=(b&127)<<shift;shift+=7;if(shift>35)throw new IOException("SLEB overflow");}while((b&128)!=0);if(shift<32&&(b&64)!=0)n|=(-1)<<shift;return n;}
    private String string(int index)throws IOException {
        if(index<0||index>=stringsCount)throw new IOException("String index");String cached=strings.get(index);if(cached!=null)return cached;
        int[] at={u32(stringsAt+index*4)};leb(at);int end=at[0];while(end<bytes.length&&bytes[end]!=0)end++;if(end==bytes.length||end-at[0]>1000000)throw new IOException("String boundary");
        String value=new String(bytes,at[0],end-at[0],StandardCharsets.UTF_8);strings.put(index,value);return value;
    }
    private String type(int i)throws IOException {if(i<0||i>=typesCount)throw new IOException("Type index");return string(u32(typesAt+i*4));}
    private String proto(int i)throws IOException {
        if(i<0||i>=protosCount)throw new IOException("Proto index");int at=protosAt+i*12, params=u32(at+8);StringBuilder b=new StringBuilder("(");
        if(params!=0){int count=u32(params);if(count<0||count>1000)throw new IOException("Parameter count");for(int j=0;j<count;j++)b.append(type(u16(params+4+j*2)));}
        return b.append(')').append(type(u32(at+4))).toString();
    }
    private String method(int i)throws IOException {if(i<0||i>=methodsCount)throw new IOException("Method index");int at=methodsAt+i*8;return type(u16(at))+"->"+string(u32(at+4))+proto(u16(at+2));}
    private String field(int i)throws IOException {if(i<0||i>=fieldsCount)throw new IOException("Field index");int at=fieldsAt+i*8;return type(u16(at))+"->"+string(u32(at+4))+":"+type(u16(at+2));}
    private String codeHash(int at)throws Exception {
        need(at,16);int count=u32(at+12);need(at+16,Math.multiplyExact(count,2));StringBuilder b=new StringBuilder();
        b.append("registers:").append(u16(at)).append("/ins:").append(u16(at+2)).append("/outs:").append(u16(at+4)).append(';');int pos=0;
        while(pos<count){int unit=u16(at+16+pos*2),op=unit&255,width=WIDTH[op];
            if(op==0&&unit!=0){int size=u16(at+16+(pos+1)*2);if(unit==0x100)width=4+size*2;else if(unit==0x200)width=2+size*4;else if(unit==0x300){int n=u32(at+16+(pos+2)*2);width=4+(int)(((long)size*n+1)/2);}else throw new IOException("Unknown DEX payload");}
            if(width<1||width>count-pos)throw new IOException("Instruction boundary");
            // Pool indices vary between DEX files. Resolve references to stable semantic names.
            String ref=null;int replaced=0;
            if(op==0x1a){ref=string(u16(at+16+(pos+1)*2));replaced=1;}
            else if(op==0x1b){ref=string(u32(at+16+(pos+1)*2));replaced=2;}
            else if(Arrays.asList(0x1c,0x1f,0x20,0x22,0x23,0x24,0x25).contains(op)){ref=type(u16(at+16+(pos+1)*2));replaced=1;}
            else if(op>=0x52&&op<=0x6d){ref=field(u16(at+16+(pos+1)*2));replaced=1;}
            else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){ref=method(u16(at+16+(pos+1)*2));replaced=1;}
            else if(op==0xfa||op==0xfb||op==0xfc||op==0xfd||op==0xfe||op==0xff)throw new IOException("Unsupported dynamic instruction in ABI");
            b.append(Integer.toHexString(unit)).append(':');
            if(ref!=null)b.append(ref.length()).append('#').append(ref).append(':');
            for(int j=1+replaced;j<width;j++)b.append(Integer.toHexString(u16(at+16+(pos+j)*2))).append(',');b.append(';');pos+=width;
        }
        int tries=u16(at+6);
        if(tries>0){
            int items=at+16+count*2+(count%2)*2;need(items,Math.multiplyExact(tries,8));int handlers=items+tries*8;int[] cursor={handlers};int total=leb(cursor);
            if(total>10000)throw new IOException("Handler count");Map<Integer,String> resolved=new HashMap<>();
            for(int i=0;i<total;i++){int offset=cursor[0]-handlers;int size=signedLeb(cursor);if(size==Integer.MIN_VALUE||Math.abs(size)>10000)throw new IOException("Catch count");StringBuilder h=new StringBuilder();
                for(int j=0;j<Math.abs(size);j++){String t=type(leb(cursor));int addr=leb(cursor);if(addr>=count)throw new IOException("Catch address");h.append(t.length()).append('#').append(t).append('@').append(addr).append(';');}
                if(size<=0){int addr=leb(cursor);if(addr>=count)throw new IOException("Catch-all address");h.append("all@").append(addr);}resolved.put(offset,h.toString());
            }
            for(int i=0;i<tries;i++){int item=items+i*8,start=u32(item),length=u16(item+4);String h=resolved.get(u16(item+6));if(start<0||start>count-length||h==null)throw new IOException("Try range");b.append("try:").append(start).append('+').append(length).append('=').append(h).append(';');}
        }
        return sha(b.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void scan(Set<String> wanted,Result result)throws Exception {
        int count=u32(header+96), at=u32(header+100);need(at,Math.multiplyExact(count,32));
        for(int i=0;i<count;i++){
            int c=at+i*32;String name=type(u32(c));if(!wanted.contains(name))continue;int data=u32(c+24);if(data==0)throw new IOException("Missing class data");int[] p={data};
            int statics=leb(p),instances=leb(p),direct=leb(p),virtual=leb(p);if(statics+instances+direct+virtual>100000)throw new IOException("Class count");
            List<Integer> staticIds=new ArrayList<>();int index=0;
            for(int j=0;j<statics;j++){index+=leb(p);leb(p);staticIds.add(index);}index=0;for(int j=0;j<instances;j++){index+=leb(p);leb(p);}
            for(int methods: new int[]{direct,virtual}){index=0;for(int j=0;j<methods;j++){index+=leb(p);leb(p);int code=leb(p);if(code!=0){String signature=method(index);if(signature.contains("->getDefaultTransactionName(")||signature.contains("->onTransact(")||wantedMethod(signature)){
                String previous=result.methods.put(signature,codeHash(code));if(previous!=null)throw new IOException("Duplicate ABI method");
            }}}}
            int values=u32(c+28);if(values==0)continue;int[] v={values};int n=leb(v);if(n>staticIds.size())throw new IOException("Static values mismatch");
            for(int j=0;j<n;j++){need(v[0],1);int tag=bytes[v[0]++]&255,kind=tag&31,len=(tag>>>5)+1;long value=0;
                if(kind==0x1e||kind==0x1f)len=0;
                if(kind==0x1c||kind==0x1d)throw new IOException("Nested ABI static values");
                need(v[0],len);for(int k=0;k<len;k++)value|=(long)(bytes[v[0]++]&255)<<(8*k);
                String f=field(staticIds.get(j));if(f.contains("->TRANSACTION_")){if(kind!=4||len>4)throw new IOException("Transaction not INT");if((bytes[v[0]-1]&128)!=0)value|=(-1L)<<(8*len);if(value<=0||value>10000)throw new IOException("Invalid transaction");result.transactions.put(f,(int)value);}
            }
        }
    }
    private boolean wantedMethod(String signature){return signature.contains("$Stub$Proxy;->getOpsForPackage(")||((signature.startsWith("Landroid/app/AppOpsManager$")||signature.startsWith("Landroid/app/AppOpsManager$AttributedOpEntry$LongSparseArrayParceling;"))&&(signature.contains("->writeToParcel(")||signature.contains("->parcel(")))||signature.contains("$Stub$Proxy;->getPermissionFlags(")||signature.contains("$Stub$Proxy;->updatePermissionFlags(")||signature.contains("$Stub$Proxy;->getPackageInfo(")||signature.contains("$Stub$Proxy;->getAllPackages(")||signature.contains("$Stub$Proxy;->getPackagesForUid(")||signature.contains("$Stub$Proxy;->checkOperationRaw(")||signature.startsWith("Lcom/android/systemui/ScRune;-><clinit>(")||signature.startsWith("Lcom/android/systemui/privacy/AppOpsPrivacyItemMonitor;->isSystemApp(")||signature.startsWith("Lcom/android/systemui/privacy/AppOpsPrivacyItemMonitor;->shouldDisplayLocationOp(")||signature.startsWith("Lcom/android/systemui/privacy/AppOpsPrivacyItemMonitor;->toPrivacyItemLocked(")||signature.startsWith("Lcom/android/systemui/privacy/AppOpsPrivacyItemMonitor;-><clinit>(");}
    public static Result inspect(File jar,Set<String> classes)throws Exception {
        Result result=new Result();try(ZipFile zip=new ZipFile(jar)){Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){
            ZipEntry e=entries.nextElement();if(!e.getName().matches("classes(?:[0-9]+)?\\.dex"))continue;
            if(e.getSize()<112||e.getSize()>100000000)throw new IOException("DEX size limit");
            byte[] b;try(InputStream input=zip.getInputStream(e)){b=input.readNBytes(100000001);}if(b.length>100000000)throw new IOException("DEX inflated limit");
            int h=0;do{DexAbi dex=new DexAbi(b,h);dex.scan(classes,result);int hs=dex.u32(h+36);if(hs!=120)break;int sectionSize=dex.u32(h+32);if(sectionSize<=0||h>b.length-sectionSize)throw new IOException("Container section");h+=sectionSize;}while(h<b.length);
        }}return result;
    }
    public static String sha(byte[] bytes)throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte d:digest)b.append(String.format(Locale.ROOT,"%02x",d&255));return b.toString();}
    /** Developer offline exporter: produces data only, never phone commands. */
    public static void main(String[] args)throws Exception {
        Set<String> classes=new HashSet<>(Arrays.asList("Landroid/permission/IPermissionManager$Stub;","Landroid/permission/IPermissionManager$Stub$Proxy;","Landroid/content/pm/IPackageManager$Stub;","Landroid/content/pm/IPackageManager$Stub$Proxy;","Lcom/android/internal/app/IAppOpsService$Stub;","Lcom/android/internal/app/IAppOpsService$Stub$Proxy;","Lcom/android/systemui/ScRune;","Lcom/android/systemui/privacy/AppOpsPrivacyItemMonitor;"));
        classes.addAll(Arrays.asList("Landroid/app/AppOpsManager$PackageOps;","Landroid/app/AppOpsManager$OpEntry;","Landroid/app/AppOpsManager$AttributedOpEntry;","Landroid/app/AppOpsManager$AttributedOpEntry$LongSparseArrayParceling;","Landroid/app/AppOpsManager$NoteOpEvent;"));
        Result r=inspect(new File(args[0]),classes);for(Map.Entry<String,String> e:r.methods.entrySet())System.out.println("M\t"+e.getKey()+"\t"+e.getValue());for(Map.Entry<String,Integer> e:r.transactions.entrySet())if(e.getKey().matches(".*->TRANSACTION_(getPermissionFlags|updatePermissionFlags|getPackageInfo|getAllPackages|getPackagesForUid|checkOperationRaw|getOpsForPackage):I"))System.out.println("T\t"+e.getKey()+"\t"+e.getValue());
    }
}
