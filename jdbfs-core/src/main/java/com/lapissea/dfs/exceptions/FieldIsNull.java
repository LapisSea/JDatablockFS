package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.field.IOField;

import java.util.function.Supplier;

import static com.lapissea.dfs.config.GlobalConfig.COSTLY_STACK_TRACE;

public class FieldIsNull extends NullPointerException{
	
	public static <T> T requireNonNull(IOField<?, ?> field, T obj){
		if(obj == null){
			throw new FieldIsNull(
				field,
				() -> {
					var struct = field.declaringStruct();
					return (struct != null? Utils.typeToHuman(struct.getType()) + "." : "") +
					       field.toShortString() +
					       " should not be null";
				}
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
	public Throwable fillInStackTrace(){
		if(COSTLY_STACK_TRACE) return super.fillInStackTrace();
		return this;
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
				      (Utils.typeToHuman(this.field.getClass()) + " - " + this.field) + " is null";
			}else msg = msgMake.get();
		}
		return msg;
	}
}
