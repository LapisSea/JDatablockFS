package com.lapissea.cfs.exceptions;

import com.lapissea.cfs.type.field.IOField;

public class FixedFormatNotSupportedException extends UnsupportedStructLayout{
	private final IOField<?, ?> field;
	
	private static String makeMsg(IOField<?, ?> field){
		return field + " (" + field.getClass().getSimpleName() + ") can not create a fixed form of itself";
	}
	
	public FixedFormatNotSupportedException(IOField<?, ?> field){
		super(makeMsg(field));
		this.field = field;
	}
	public FixedFormatNotSupportedException(IOField<?, ?> field, Throwable cause){
		super(makeMsg(field), cause);
		this.field = field;
	}
	public IOField<?, ?> getField(){
		return field;
	}
}
