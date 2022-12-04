package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.ClassName;
import com.lapissea.jorth.v2.lang.Endable;
import com.lapissea.jorth.v2.lang.info.FunctionInfo;
import org.objectweb.asm.ClassWriter;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public final class ClassGen implements ClassInfo, Endable{
	
	public record FieldGen(ClassName owner, Visibility visibility, String name, GenericType type, boolean isStatic) implements FieldInfo{
	
	}
	
	public final  TypeSource      typeSource;
	public final  ClassName       name;
	public final  ClassType       type;
	public final  Visibility      visibility;
	private final EnumSet<Access> accessSet;
	public final  GenericType     extension;
	
	final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
	
	private final Map<String, FieldGen>                    fields    = new HashMap<>();
	private final Map<FunctionInfo.Signature, FunctionGen> functions = new HashMap<>();
	
	//	private boolean addedClinit;
	private boolean addedInit;
	
	private byte[] classFile;
	
	public ClassGen(
		TypeSource typeSource,
		ClassName name, ClassType type, Visibility visibility,
		GenericType extension, List<GenericType> interfaces,
		Set<Access> accessSet
	){
		this.typeSource = typeSource;
		this.name = name;
		this.type = type;
		this.visibility = visibility;
		this.extension = extension;
		this.accessSet = accessSet.isEmpty()? EnumSet.noneOf(Access.class) : EnumSet.copyOf(accessSet);
		
		int accessFlags = visibility.flag|switch(type){
			case CLASS -> ACC_SUPER|ACC_FINAL;
			case INTERFACE -> ACC_ABSTRACT|ACC_INTERFACE;
			case ENUM -> ACC_SUPER|ACC_FINAL|ACC_ENUM;
		};
		
		
		int len = extension.jvmSignatureLen();
		for(var interf : interfaces){
			len += interf.jvmSignatureLen();
		}
		var signature = new StringBuilder(len);
		extension.jvmSignature(signature);
		for(var interf : interfaces){
			interf.jvmSignature(signature);
		}
		assert signature.length() == len : signature.length() + " " + len;
		
		String[] interfaceStrings;
		if(interfaces.isEmpty()) interfaceStrings = null;
		else{
			interfaceStrings = new String[interfaces.size()];
			for(int i = 0; i<interfaces.size(); i++){
				interfaceStrings[i] = interfaces.get(i).raw().slashed();
			}
		}
		
		writer.visit(V19, accessFlags, name.slashed(), signature.toString(), extension.raw().slashed(), interfaceStrings);
	}
	
	@Override
	public void end() throws MalformedJorthException{
//		if(!addedClinit){
//			defineFunction("<clinit>", Visibility.PUBLIC, Set.of(), null, new LinkedHashMap<>()).end();
//		}
		if(!addedInit){
			var init = defineFunction("<init>", this.visibility, Set.of(), null, new LinkedHashMap<>());
			init.getOp("this", "this");
			init.superOp();
			init.end();
		}
		
		writer.visitEnd();
		classFile = writer.toByteArray();
	}
	
	public void defineField(Visibility visibility, Set<Access> accesses, GenericType type, String name) throws MalformedJorthException{
		checkEnd();
		if(fields.containsKey(name)) throw new MalformedJorthException("Field " + name + " already exists");
		fields.put(name, new FieldGen(this.name, visibility, name, type, accesses.contains(Access.STATIC)));
		
		
		var descriptor = type.jvmDescriptor();
		var signature  = type.jvmSignature();
		if(signature.equals(descriptor)) signature = null;
		
		var access = visibility.flag;
		if(accesses.contains(Access.STATIC)){
			access |= ACC_STATIC;
		}
		if(accesses.contains(Access.FINAL)){
			access |= ACC_FINAL;
		}
		
		var fieldVisitor = writer.visitField(access, name, descriptor.toString(), signature == null? null : signature.toString(), null);
		
		//TODO: annotations
		
		fieldVisitor.visitEnd();
	}
	
	public FunctionGen defineFunction(String name, Visibility visibility, Set<Access> access, GenericType returnType, LinkedHashMap<String, FunctionGen.ArgInfo> args) throws MalformedJorthException{
		checkEnd();
		var fun = new FunctionGen(this, name, visibility, access, returnType, args);
		
		List<GenericType> argStr = new ArrayList<>(args.size());
		for(var value : args.values()){
			argStr.add(value.type());
		}
		var sig = new FunctionInfo.Signature(name, argStr);
		if(this.functions.put(sig, fun) != null){
			throw new MalformedJorthException("Duplicate method " + sig);
		}

//		if("<clinit>".equals(name)){
//			addedClinit=true;
//		}
		if("<init>".equals(name)){
			addedInit = true;
		}
		
		return fun;
	}
	
	@Override
	public FieldInfo getField(String name) throws MalformedJorthException{
		var n = fields.get(name);
		if(n == null){
			throw new MalformedJorthException("Field " + name + " does not exist in " + this.name);
		}
		return n;
	}
	
	@Override
	public ClassName name(){
		return name;
	}
	
	@Override
	public ClassInfo superType() throws MalformedJorthException{
		return typeSource.byType(extension);
	}
	@Override
	public ClassType type(){
		return type;
	}
	
	@Override
	public boolean isFinal(){
		return accessSet.contains(Access.FINAL);
	}
	
	private void checkEnd(){
		if(classFile != null){
			throw new RuntimeException("Class ended");
		}
	}
	
	public byte[] getClassFile(){
		return classFile;
	}
	
	@Override
	public FunctionInfo getFunction(FunctionInfo.Signature signature) throws MalformedJorthException{
		var fun = functions.get(signature);
		if(fun != null) return fun;
		throw new MalformedJorthException("Function of " + signature + " does not exist");
	}
	
	@Override
	public String toString(){
		return name.toString();
	}
}
