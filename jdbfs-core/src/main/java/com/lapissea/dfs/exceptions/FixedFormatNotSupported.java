package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.type.field.IOField;

public class FixedFormatNotSupported extends UnsupportedStructLayout{
	private final String field;
	
	private static String makeMsg(IOField<?, ?> field){
		return field + " (" + field.getClass().getSimpleName() + ") can not create a fixed form of itself";
	}
	
	public FixedFormatNotSupported(IOField<?, ?> field){
		super(makeMsg(field));
		this.field = field.getName();
	}
	public FixedFormatNotSupported(IOField<?, ?> field, Throwable cause){
		super(makeMsg(field), cause);
		this.field = field.getName();
	}
	public String getFieldName(){
		return field;
	}
}
