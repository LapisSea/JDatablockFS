package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOStruct.*;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.text.Encoding.CharEncoding;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;

public class AutoText extends AbstractText{
	
	public static class StringIO implements ReaderWriter<String>{
		@Override
		public String read(Object targetObj, Cluster cluster, ContentReader source, String oldValue) throws IOException{
			AutoText text=new AutoText();
			text.readStruct(cluster, source);
			return text.getData();
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, String source) throws IOException{
			AutoText text=new AutoText(source);
			text.writeStruct(cluster, target);
		}
		
		@Override
		public long mapSize(Object targetObj, String source){
			return new AutoText(source).getInstanceSize();
		}
		
		@Override
		public OptionalInt getFixedSize(){
			return OptionalInt.empty();
		}
		
		@Override
		public OptionalInt getMaxSize(){
			return OptionalInt.empty();
		}
	}
	
	@EnumValue(index=1)
	private NumberSize   numSize=NumberSize.BYTE;
	@EnumValue(index=2)
	private CharEncoding set    =CharEncoding.UTF8;
	
	@PrimitiveValue(index=3, sizeRef="numSize")
	private int byteSize;
	@PrimitiveValue(index=4, sizeRef="numSize")
	private int charCount;
	
	
	public AutoText(){ this(""); }
	
	public AutoText(String data){
		setData(data);
	}
	
	@Size
	private long sizeData(String value){
		return byteCount();
	}
	
	@Write
	private void writeData(Cluster cluster, ContentWriter dest, String source) throws IOException{
		set.write(dest, source);
	}
	
	@Read
	private String readData(Cluster cluster, ContentReader source, String oldVal) throws IOException{
		try(var buf=source.bufferExactRead(byteCount())){
			return set.read(buf, this);
		}
		
	}
	
	@Set
	public void setData(@NotNull String data){
		Objects.requireNonNull(data);
		
		set=CharEncoding.UTF8;
		for(var value : CharEncoding.values()){
			if(value.canEncode(data)){
				set=value;
				break;
			}
		}
		
		this.data=data;
		
		charCount=data.length();
		byteSize=set.calcSize(data);
		numSize=NumberSize.bySize(Math.max(charCount, byteCount())).max(NumberSize.BYTE);
	}
	
	
	@Override
	protected int charCount(){
		return charCount;
	}
	
	@Override
	protected int byteCount(){
		return byteSize;
	}
	
	@NotNull
	@Override
	public String toString(){
		return set+": "+data;
	}
	
}
