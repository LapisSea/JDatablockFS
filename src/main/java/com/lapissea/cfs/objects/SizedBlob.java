package com.lapissea.cfs.objects;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct.*;
import com.lapissea.util.ZeroArrays;

import java.io.IOException;

public class SizedBlob extends IOInstance{
	
	@EnumValue(index=1)
	private NumberSize lenSize;
	
	@PrimitiveValue(index=2, sizeRef="lenSize")
	private int byteCount;
	
	@Value(index=3)
	private byte[] data;
	
	public SizedBlob(){
		this(ZeroArrays.ZERO_BYTE);
	}
	
	public SizedBlob(byte[] data){
		setData(data);
	}
	
	public byte[] getData(){
		return data;
	}
	
	public void setData(byte[] data){
		this.data=data;
		byteCount=data==null?0:data.length;
		lenSize=NumberSize.bySize(byteCount).max(NumberSize.SMALEST_REAL);
	}
	
	@Size
	private long sizeData(byte[] value){
		return byteCount;
	}
	
	@Write
	private void writeData(ContentWriter dest, byte[] source) throws IOException{
		dest.write(source);
	}
	
	@Read
	private byte[] readData(ContentReader source, byte[] oldVal) throws IOException{
		return source.readInts1(byteCount);
	}
}
