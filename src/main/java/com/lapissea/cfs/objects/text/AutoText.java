package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.text.Encoding.CharEncoding;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.Objects;

public final class AutoText extends IOInstance<AutoText> implements CharSequence{
	
	public static final Struct<AutoText>     STRUCT=Struct.of(AutoText.class);
	public static final StructPipe<AutoText> PIPE  =ContiguousStructPipe.of(STRUCT);
	
	private String       data;
	private byte[]       dataSrc;
	@IOValue
	private CharEncoding encoding;
	@IOValue
	@IODependency.VirtualNumSize(name = "numSize")
	private int          charCount;
	
	
	public AutoText(){
		super(STRUCT);
		data="";
		encoding=CharEncoding.DEFAULT;
		charCount=0;
	}
	
	public AutoText(String data){
		setData(data);
	}
	
	public void setData(@NotNull String newData){
		Objects.requireNonNull(newData);
		
		encoding=CharEncoding.findBest(newData);
		charCount=newData.length();
		data=newData;
		dataSrc=null;
	}
	
	@IOValue
	private void setCharCount(int charCount){
		this.charCount=charCount;
		dataSrc=null;
	}
	@IOValue
	private void setEncoding(CharEncoding encoding){
		this.encoding=encoding;
		dataSrc=null;
	}
	
	@IOValue
	@IODependency({"charCount", "encoding"})
	@IODependency.ArrayLenSize(name = "numSize")
	private byte[] getTextBytes() throws IOException{
		if(dataSrc==null){
			dataSrc=generateBytes();
		}
		return dataSrc;
	}
	
	private byte[] generateBytes() throws IOException{
		byte[] buff=new byte[encoding.calcSize(data)];
		writeTextBytes(new ContentOutputStream.BA(buff));
		return buff;
	}
	
	@IOValue
	private void setTextBytes(byte[] bytes) throws IOException{
		dataSrc=bytes;
		StringBuilder sb=new StringBuilder();
		readTextBytes(new ContentInputStream.BA(bytes), sb);
		data=sb.toString();
	}
	
	public void writeTextBytes(ContentWriter dest) throws IOException{
		encoding.write(dest, data);
	}
	public void readTextBytes(ContentInputStream src, StringBuilder dest) throws IOException{
		encoding.read(src, charCount, dest);
	}
	
	
	@NotNull
	@Override
	public String toString(){
		return data;
//		return set+": "+data;
	}
	
	@Override
	public int length(){
		return charCount;
	}
	@Override
	public CharSequence subSequence(int start, int end){
		return data.subSequence(start, end);
	}
	@Override
	public char charAt(int index){
		return data.charAt(index);
	}
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof AutoText text)) return false;
		return data.equals(text.data);
	}
	
	@Override
	public int hashCode(){
		return data.hashCode();
	}
	public String getData(){
		return data;
	}
}
