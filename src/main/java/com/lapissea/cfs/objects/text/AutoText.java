package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.objects.text.Encoding.CharEncoding;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.Objects;

public class AutoText extends IOInstance<AutoText> implements CharSequence{
	
	
	private String       data;
	@IOValue
	private CharEncoding encoding;
	@IOValue
	@IODependency.VirtualNumSize(name="numSize")
	private int          charCount;
	
	public AutoText(){
		data="";
		encoding=CharEncoding.BASE_16;
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
	}
	
	@IOValue
	@IODependency({"charCount", "encoding"})
	@IODependency.ArrayLenSize(name="numSize")
	private byte[] getTextBytes() throws IOException{
		byte[] buff=new byte[encoding.calcSize(data)];
		encoding.write(new ContentOutputStream.BA(buff), data);
		return buff;
	}
	
	@IOValue
	private void setTextBytes(byte[] bytes) throws IOException{
		data=encoding.read(new ContentInputStream.BA(bytes), charCount);
	}
	
	@NotNull
	@Override
	public String toString(){
		return data;
//		return set+": "+data;
	}
	
	@Override
	public int length(){
		return data.length();
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
