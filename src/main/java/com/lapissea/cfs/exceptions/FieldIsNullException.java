package com.lapissea.cfs.exceptions;

import com.lapissea.cfs.type.field.IOField;

public class FieldIsNullException extends NullPointerException{
	
	public static <T> T requireNonNull(IOField<?, ?> field, T obj){
		if(obj==null){
			throw new FieldIsNullException(field, field.toShortString()+" should not be null");
		}
		return obj;
	}
	
	
	public final IOField<?, ?> field;
	
	public FieldIsNullException(IOField<?, ?> field){
		super(field+" is null");
		this.field=field;
	}
	public FieldIsNullException(IOField<?, ?> field, String s){
		super(s);
		this.field=field;
	}
}
