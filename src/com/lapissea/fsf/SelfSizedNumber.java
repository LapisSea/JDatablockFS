package com.lapissea.fsf;

import com.lapissea.fsf.io.serialization.FileObject;

import java.util.Objects;

public class SelfSizedNumber extends FileObject.FullLayout<SelfSizedNumber> implements INumber{
	
	private static final ObjectDef<SelfSizedNumber> LAYOUT=FileObject.sequenceBuilder(
		new SingleEnumDef<>(NumberSize.class,
		                    SelfSizedNumber::getSize,
		                    SelfSizedNumber::setSize),
		new NumberDef<>(SelfSizedNumber::getSize,
		                SelfSizedNumber::getValue,
		                SelfSizedNumber::setValue)
	                                                                                 );
	
	private NumberSize size;
	private long       value;
	
	public SelfSizedNumber(long value){
		this(NumberSize.bySize(value), value);
	}
	
	public SelfSizedNumber(NumberSize size, long value){
		this();
		this.size=Objects.requireNonNull(size);
		this.value=value;
	}
	
	public SelfSizedNumber(){
		super(LAYOUT);
	}
	
	@Override
	public long getValue(){
		return value;
	}
	
	public NumberSize getSize(){
		return size;
	}
	
	private void setValue(long value){
		this.value=value;
	}
	
	private void setSize(NumberSize size){
		this.size=size;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof SelfSizedNumber)) return false;
		SelfSizedNumber that=(SelfSizedNumber)o;
		return getValue()==that.getValue()&&
		       getSize()==that.getSize();
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+(getSize()==null?0:getSize().hashCode());
		result=31*result+Long.hashCode(getValue());
		return result;
	}
}
