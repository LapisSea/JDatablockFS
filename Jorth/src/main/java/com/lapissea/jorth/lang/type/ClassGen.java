package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.Endable;
import com.lapissea.jorth.lang.info.FunctionInfo;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

public final class ClassGen implements ClassInfo, Endable{
	
	public record FieldGen(ClassName owner, Visibility visibility, String name, GenericType type, Set<Access> access) implements FieldInfo{
		@Override
		public boolean isStatic(){
			return access.contains(Access.STATIC);
		}
		@Override
		public boolean isEnumConstant(){
			return access.contains(Access.ENUM);
		}
	}
	
	public final  TypeSource      typeSource;
	public final  ClassName       name;
	public final  ClassType       type;
	public final  Visibility      visibility;
	private final EnumSet<Access> accessSet;
	public final  GenericType     extension;
	
	final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
	
	private final Map<String, FieldGen>                    fields    = new LinkedHashMap<>();
	private final Map<FunctionInfo.Signature, FunctionGen> functions = new HashMap<>();
	
	private boolean addedInit;
	
	private byte[] classFile;
	
	public ClassGen(
		TypeSource typeSource,
		ClassName name, ClassType type, Visibility visibility,
		GenericType extension, List<GenericType> interfaces,
		Set<Access> accessSet,
		List<AnnGen> anns){
		this.typeSource = typeSource;
		this.name = name;
		this.type = type;
		this.visibility = visibility;
		this.extension = extension;
		this.accessSet = accessSet.isEmpty()? EnumSet.noneOf(Access.class) : EnumSet.copyOf(accessSet);
		
		int accessFlags = visibility.flag|switch(type){
			case CLASS -> ACC_SUPER|ACC_FINAL;
			case INTERFACE, ANNOTATION -> ACC_ABSTRACT|ACC_INTERFACE;
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
		writeAnnotations(anns, writer::visitAnnotation);
	}
	
	@Override
	public void end() throws MalformedJorth{
		if(!addedInit && (type == ClassType.CLASS || type == ClassType.ENUM) && !accessSet.contains(Access.STATIC)){
			var parent = typeSource.byType(extension);
			
			if(parent.getFunctionsByName("<init>").anyMatch(f -> f.argumentTypes().isEmpty())){
				var init = defineFunction("<init>", this.visibility, Set.of(), null, List.of(), List.of());
				init.loadThisIns();
				init.superOp(List.of());
				init.end();
			}else{
				for(var e : parent.getFunctionsByName("<init>").toList()){
					var                       args    = e.argumentTypes();
					List<FunctionGen.ArgInfo> argInfo = new ArrayList<>();
					for(int i = 0; i<args.size(); i++){
						argInfo.add(new FunctionGen.ArgInfo(e.argumentTypes().get(i), "arg" + i));
					}
					
					var init = defineFunction("<init>", this.visibility, Set.of(), null, argInfo, List.of());
					init.loadThisIns();
					for(int i = 0; i<args.size(); i++){
						init.getOp("#arg", "arg" + i);
					}
					init.superOp(e.argumentTypes());
					init.end();
				}
			}
		}
		
		if(type == ClassType.ENUM){
			generateEnumBoilerplate();
		}
		
		writer.visitEnd();
		classFile = writer.toByteArray();
	}
	
	private void generateEnumBoilerplate() throws MalformedJorth{
		var arrType = new GenericType(name, 1, List.of());
		defineField(
			Visibility.PRIVATE,
			EnumSet.of(Access.STATIC, Access.FINAL),
			List.of(),
			arrType,
			"$VALUES"
		);
		{
			var fun = defineFunction(
				"values",
				Visibility.PUBLIC,
				EnumSet.of(Access.STATIC),
				arrType,
				List.of(),
				List.of());
			
			fun.getOp(name.dotted(), "$VALUES");
			fun.startCall(null, "clone").end();
			fun.castOp(arrType);
			fun.end();
		}
		{
			var fun = defineFunction(
				"<clinit>",
				Visibility.PUBLIC,
				EnumSet.of(Access.STATIC),
				null,
				List.of(),
				List.of());
			
			var constants = fields.values().stream().filter(FieldGen::isEnumConstant).toList();
			
			fun.loadIntOp(constants.size());
			fun.newOp(arrType);
			
			for(int i = 0; i<constants.size(); i++){
				FieldInfo field = constants.get(i);
				if(!field.isEnumConstant()) continue;
				
				fun.dupOp();//array dup
				fun.loadIntOp(i);//[i] = ...
				
				fun.newOp(new GenericType(name));// new enum(name,ordinal)
				fun.dupOp();
				var call = fun.startCall(null, "<init>");
				fun.loadStringOp(field.name());
				fun.loadIntOp(i);
				call.end();
				
				fun.dupOp();
				fun.setOp(name.dotted(), field.name());// Enum.NAME=obj
				fun.setElementOP();
			}
			
			fun.setOp(name.dotted(), "$VALUES");
			fun.end();
		}
	}
	
	public void defineField(Visibility visibility, Set<Access> accesses, Collection<AnnGen> annotations, GenericType type, String name) throws MalformedJorth{
		checkEnd();
		if(fields.containsKey(name)) throw new MalformedJorth("Field " + name + " already exists");
		fields.put(name, new FieldGen(this.name, visibility, name, type,
		                              Collections.unmodifiableSet(accesses.isEmpty()?
		                                                          EnumSet.noneOf(Access.class) :
		                                                          EnumSet.copyOf(accesses))));
		
		
		var descriptor = type.jvmDescriptor();
		var signature  = type.jvmSignature();
		if(signature.equals(descriptor)) signature = null;
		
		var access = visibility.flag|accesses.stream().mapToInt(f -> f.flag).reduce((a, b) -> a|b).orElse(0);
		
		var fieldVisitor = writer.visitField(access, name, descriptor.toString(), signature == null? null : signature.toString(), null);
		
		writeAnnotations(annotations, fieldVisitor::visitAnnotation);
		
		fieldVisitor.visitEnd();
	}
	
	public static void writeAnnotations(Collection<AnnGen> annotations, BiFunction<String, Boolean, AnnotationVisitor> visitor){
		for(AnnGen annotation : annotations){
			var annWriter = visitor.apply(new GenericType(annotation.type()).jvmDescriptor().toString(), true);
			for(var e : annotation.args().entrySet()){
				var argName  = e.getKey();
				var argValue = e.getValue();
				
				if(argValue.getClass().isArray()){
					var arrAnn = annWriter.visitArray(argName);
					for(int i = 0; i<Array.getLength(argValue); i++){
						arrAnn.visit(null, Array.get(argValue, i));
					}
					arrAnn.visitEnd();
					
				}else if(argValue instanceof Enum<?> eVal){
					annWriter.visitEnum(argName, GenericType.of(e.getClass()).jvmSignatureStr(), eVal.name());
				}else{
					annWriter.visit(argName, argValue);
				}
				
			}
			
			annWriter.visitEnd();
		}
	}
	
	public FunctionGen defineFunction(String name, Visibility visibility, Set<Access> access, GenericType returnType, Collection<FunctionGen.ArgInfo> args, List<AnnGen> anns) throws MalformedJorth{
		checkEnd();
		if(name.equals("<init>") && accessSet.contains(Access.STATIC)){
			throw new MalformedJorth("Static class can not be instantiated");
		}
		var fun = new FunctionGen(this, name, visibility, access, returnType, args, anns);
		
		List<GenericType> argStr = new ArrayList<>(args.size());
		for(var value : args){
			argStr.add(value.type());
		}
		var sig = new FunctionInfo.Signature(name, argStr);
		if(this.functions.put(sig, fun) != null){
			throw new MalformedJorth("Duplicate method " + sig);
		}
		
		if("<init>".equals(name)){
			addedInit = true;
		}
		
		return fun;
	}
	
	@Override
	public FieldInfo getField(String name) throws MalformedJorth{
		var n = fields.get(name);
		if(n == null){
			throw new MalformedJorth("Field " + name + " does not exist in " + this.name);
		}
		return n;
	}
	
	@Override
	public ClassName name(){
		return name;
	}
	
	@Override
	public ClassInfo superType() throws MalformedJorth{
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
	public FunctionInfo getFunction(FunctionInfo.Signature signature) throws MalformedJorth{
		var fun = functions.get(signature);
		if(fun != null) return fun;
		throw new MalformedJorth("Function of " + signature + " does not exist");
	}
	@Override
	public Stream<? extends FunctionInfo> getFunctionsByName(String name){
		return functions.values().stream().filter(f -> f.name().equals(name));
	}
	
	@Override
	public String toString(){
		return name.toString();
	}
}
