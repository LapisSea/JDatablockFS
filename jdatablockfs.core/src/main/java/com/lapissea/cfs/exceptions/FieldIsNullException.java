package com.lapissea.cfs.exceptions;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.IOField;

import java.util.function.Supplier;

public class FieldIsNullException extends NullPointerException{
	
	public static <T> T requireNonNull(IOField<?, ?> field, T obj){
		if(obj == null){
			throw new FieldIsNullException(
				field,
				() -> (field.declaringStruct() != null? Utils.typeToHuman(field.declaringStruct().getType(), false) + "." : "") +
				      field.toShortString() +
				      " should not be null"
			);
		}
		return obj;
	}
	
	
	private final Supplier<String> msgMake;
	private       String           msg;
	
	public final IOField<?, ?> field;
	
	public FieldIsNullException(IOField<?, ?> field){
		this.field = field;
		msgMake = () -> this.field == null? "<unknown field>" : (Utils.typeToHuman(this.field.getClass(), false) + " - " + this.field) + " is null";
	}
	
	public FieldIsNullException(IOField<?, ?> field, Supplier<String> msgMake){
		this.msgMake = msgMake;
		this.field = field;
	}
	
	@Override
	public String getLocalizedMessage(){
		return getMessage();
	}
	
	@Override
	public String getMessage(){
		if(msg == null) msg = msgMake.get();
		return msg;
	}
}
