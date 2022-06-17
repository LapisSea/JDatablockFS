package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.util.LogUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.rmi.RemoteException;
import java.util.HexFormat;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class BakedData{
	
	private static String replaceStr;
	private static String getDataString(){
		if(replaceStr!=null) return replaceStr;
		return /*str*/"00000108738a0cd17572743d2820c0800aa40e1a1afe43133b85c6f743e3f75ce4616560f8c1c078221e2a725143032ecbc44000c075cfa548f76d72740300";
	}
	
	public static byte[] getData() throws IOException{
		var compressedStr=getDataString();
		if(compressedStr.isEmpty()){
			genData();
			return getData();
		}
		var compressed=ByteBuffer.wrap(HexFormat.of().parseHex(compressedStr));
		
		var siz =compressed.getInt();
		int read=0;
		try{
			var res=new byte[siz];
			var i  =new Inflater(true);
			i.setInput(compressed);
			while(res.length>read){
				read+=i.inflate(res, read, res.length-read);
			}
			return res;
		}catch(DataFormatException e){
			genData();
			return getData();
		}
		
	}
	
	public static void genData() throws IOException{
		var mem=MemoryData.builder().build();
		Cluster.init(mem);
		var data=mem.readAll();
		var def =new Deflater(Deflater.BEST_COMPRESSION, true);
		
		var out=new ByteArrayOutputStream();
		out.write(ByteBuffer.allocate(4).putInt(0, data.length).array());
		
		def.setInput(data);
		def.finish();
		byte[] buf=new byte[1024];
		
		while(!def.finished()){
			int read=def.deflate(buf);
			out.write(buf, 0, read);
		}
		byte[] compressed   =out.toByteArray();
		String compressedStr=HexFormat.of().formatHex(compressed);
		if(compressedStr.equals(getDataString())){
			return;
		}
		
		var thisClass=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                         .walk(s->s.map(StackWalker.StackFrame::getDeclaringClass).findFirst().orElseThrow());
		
		var file=new File(thisClass.getProtectionDomain().getCodeSource().getLocation().getFile());
		if(!file.getName().equals("test-classes")) throw new RuntimeException(file+"");
		if(!file.getParentFile().getName().equals("target")) throw new RuntimeException(file.getParentFile()+"");
		
		var projectRoot=file.getParentFile().getParentFile();
		if(!projectRoot.exists()||!projectRoot.isDirectory()||!projectRoot.canWrite()) throw new RemoteException(projectRoot+"");
		
		var srcRoot=new File(projectRoot, "src/test/java");
		if(!srcRoot.exists()) throw new RemoteException(srcRoot+"");
		
		var thisFile=new File(srcRoot, thisClass.getName().replace('.', '/')+".java");
		if(!thisFile.exists()) throw new RemoteException(thisFile+"");
		
		var str=Files.readString(thisFile.toPath(), StandardCharsets.UTF_8);
		
		var newStr=str.replaceFirst("/\\*str\\*/\"\\w*\"", "/*str*/\""+compressedStr+'"');
		if(str.equals(newStr)) throw new RemoteException("failed to find str");
		
		Files.writeString(thisFile.toPath(), newStr, StandardCharsets.UTF_8);
		LogUtil.println("updated data string");
		
		replaceStr=compressedStr;
	}
	
	
}
