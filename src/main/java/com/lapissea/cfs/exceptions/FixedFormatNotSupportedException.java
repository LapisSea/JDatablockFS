package com.lapissea.cfs.exceptions;

import com.lapissea.cfs.type.field.IOField;

public class FixedFormatNotSupportedException extends UnsupportedOperationException{
	private final IOField<?, ?> field;
	public FixedFormatNotSupportedException(IOField<?, ?> field){
		super(field+" can not create a fixed form of itself");
		this.field=field;
	}
	public IOField<?, ?> getField(){
		return field;
	}
}
