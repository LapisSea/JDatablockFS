package com.lapissea.jorth.redo;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

public final class BootstrapFn{
	
	public record StaticArg(JType type, Object value){ }
	
	public ClassName       owner;
	public String          functionName;
	public List<StaticArg> bootstrapStaticArgs = new ArrayList<>();
	
	public BootstrapFn caller(Class<?> owner, String functionName){
		return caller(ClassName.of(owner), functionName);
	}
	public BootstrapFn caller(ClassName owner, String functionName){
		this.owner = owner;
		this.functionName = functionName;
		return this;
	}
	
	public BootstrapFn arg(Class<?> arg, Object value){
		return arg(GenericType.of(arg), value);
	}
	public BootstrapFn arg(GenericType arg, Object value){
		bootstrapStaticArgs.add(new StaticArg(arg, switch(value){
			case ClassName cls -> Type.getType(GenericType.of(cls).jvmDescriptorStr());
			case Class<?> cls -> Type.getType(GenericType.of(cls).jvmDescriptorStr());
			default -> value;
		}));
		return this;
	}
}
