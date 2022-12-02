package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.ClassName;
import com.lapissea.jorth.v2.lang.Endable;
import com.lapissea.util.LogUtil;
import org.objectweb.asm.ClassWriter;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class ClassGen implements ClassInfo, Endable{
	
	public record FieldGen(Visibility visibility, String name, GenericType type){}
	
	public final ClassName name;
	public final ClassType type;
	
	private final ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
	
	private final Map<String, FieldGen> fields=new HashMap<>();
	
	public ClassGen(ClassName name, ClassType type, Visibility visibility, GenericType extension, List<GenericType> interfaces){
		this.name=name;
		this.type=type;
		
		int accessFlags=visibility.flag|switch(type){
			case CLASS -> ACC_SUPER|ACC_FINAL;
			case INTERFACE -> ACC_ABSTRACT|ACC_INTERFACE;
			case ENUM -> ACC_SUPER|ACC_FINAL|ACC_ENUM;
		};
		
		var signature=new StringBuilder();
		extension.jvmSignature(signature);
		for(var interf : interfaces){
			interf.jvmSignature(signature);
		}
		
		String[] interfaceStrings;
		if(interfaces.isEmpty()) interfaceStrings=null;
		else{
			interfaceStrings=new String[interfaces.size()];
			for(int i=0;i<interfaces.size();i++){
				interfaceStrings[i]=interfaces.get(i).raw().slashed();
			}
		}
		
		writer.visit(V18, accessFlags, name.slashed(), signature.toString(), extension.raw().slashed(), interfaceStrings);
	}
	
	@Override
	public void end() throws MalformedJorthException{
		LogUtil.print("class end");
	}
	
	public void defineField(Visibility visibility, Set<Access> accesses, GenericType type, String name) throws MalformedJorthException{
		if(fields.containsKey(name)) throw new MalformedJorthException("Field "+name+" already exists");
		fields.put(name, new FieldGen(visibility, name, type));
		
		
		var descriptor=type.jvmDescriptor();
		var signature =type.jvmSignature();
		if(signature.equals(descriptor)) signature=null;
		
		var access=visibility.flag;
		if(accesses.contains(Access.STATIC)){
			access|=ACC_STATIC;
		}
		if(accesses.contains(Access.FINAL)){
			access|=ACC_FINAL;
		}
		
		var fieldVisitor=writer.visitField(access, name, descriptor.toString(), signature==null?null:signature.toString(), null);
		
		//TODO: annotations
		
		fieldVisitor.visitEnd();
	}
	
	public FunctionGen defineFunction(String functionName, GenericType returns, ArrayList<FunctionGen.Arg> args){
		var fun=new FunctionGen(functionName, returns, args);
		
		return fun;
	}
	
	public void getOp(String owner, String member){
	
	}
}
