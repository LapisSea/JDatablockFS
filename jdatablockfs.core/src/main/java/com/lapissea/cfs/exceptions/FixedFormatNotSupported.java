package com.lapissea.cfs.exceptions;

import com.lapissea.cfs.type.field.IOField;

public class FixedFormatNotSupported extends UnsupportedStructLayout{
	private final IOField<?, ?> field;
	
	private static String makeMsg(IOField<?, ?> field){
		return field + " (" + field.getClass().getSimpleName() + ") can not create a fixed form of itself";
	}
	
	public FixedFormatNotSupported(IOField<?, ?> field){
		super(makeMsg(field));
		this.field = field;
	}
	public FixedFormatNotSupported(IOField<?, ?> field, Throwable cause){
		super(makeMsg(field), cause);
		this.field = field;
	}
	public IOField<?, ?> getField(){
		return field;
	}
}
