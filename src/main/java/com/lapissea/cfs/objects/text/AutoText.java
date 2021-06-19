package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.text.Encoding.CharEncoding;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;

import java.util.Objects;

public class AutoText extends AbstractText<AutoText>{
	
	@IOValue
	private NumberSize   numSize=NumberSize.BYTE;
	@IOValue
	private CharEncoding set    =CharEncoding.UTF8;
	
	@IODependency.NumSize("numSize")
	@IOValue
	private int charCount;
	
	
	public AutoText(){ this(""); }
	
	public AutoText(String data){
		setData(data);
	}
	
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
		numSize=NumberSize.bySize(charCount).max(NumberSize.BYTE);
	}
	
	
	@Override
	protected int charCount(){
		return charCount;
	}
	
	@NotNull
	@Override
	public String toString(){
		return set+": "+data;
	}
	
}
