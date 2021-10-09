package com.lapissea.cfs.exceptions;

import com.lapissea.cfs.type.field.IOField;

public class FieldIsNullException extends NullPointerException{
	
	public final IOField<?, ?> field;
	
	public FieldIsNullException(IOField<?, ?> field){
		this.field=field;
	}
	public FieldIsNullException(IOField<?, ?> field, String s){
		super(s);
		this.field=field;
	}
}
