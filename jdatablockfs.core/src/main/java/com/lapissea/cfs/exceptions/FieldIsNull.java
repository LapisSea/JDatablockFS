package com.lapissea.cfs.exceptions;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.IOField;

import java.util.function.Supplier;

public class FieldIsNull extends NullPointerException{
	
	public static <T> T requireNonNull(IOField<?, ?> field, T obj){
		if(obj == null){
			throw new FieldIsNull(
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
	
	public FieldIsNull(IOField<?, ?> field){
		this.field = field;
		msgMake = null;
	}
	
	public FieldIsNull(IOField<?, ?> field, Supplier<String> msgMake){
		this.msgMake = msgMake;
		this.field = field;
	}
	
	@Override
	public String getLocalizedMessage(){
		return getMessage();
	}
	
	@Override
	public String getMessage(){
		if(msg == null){
			if(msgMake == null){
				msg = this.field == null?
				      "<unknown field>" :
				      (Utils.typeToHuman(this.field.getClass(), false) + " - " + this.field) + " is null";
			}else msg = msgMake.get();
		}
		return msg;
	}
}
