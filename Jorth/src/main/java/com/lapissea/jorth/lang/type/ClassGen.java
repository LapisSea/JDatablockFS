package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.Endable;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.util.NotImplementedException;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

public final class ClassGen implements ClassInfo, Endable{
	
	public record FieldGen(ClassName owner, Visibility visibility, String name, JType type, Set<Access> access) implements FieldInfo{
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
	
	public final  GenericType       extension;
	private final List<GenericType> interfaces;
	
	final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
	
	private final Map<String, FieldGen>                    fields    = new LinkedHashMap<>();
	private final Map<FunctionInfo.Signature, FunctionGen> functions = new HashMap<>();
	
	private boolean addedInit;
	
	private byte[] classFile;
	
	public ClassGen(
		TypeSource typeSource,
		ClassName name, ClassType type, Visibility visibility,
		GenericType extension, List<GenericType> interfaces, List<ClassName> permits,
		Set<Access> accessSet,
		List<AnnGen> anns, Map<ClassName, GenericType> typeArgs){
		this.typeSource = typeSource;
		this.name = name;
		this.type = type;
		this.visibility = visibility;
		this.extension = extension;
		this.interfaces = List.copyOf(interfaces);
		this.accessSet = accessSet.isEmpty()? EnumSet.noneOf(Access.class) : EnumSet.copyOf(accessSet);
		
		int accessFlags = visibility.flag|switch(type){
			case CLASS -> ACC_SUPER|(permits.isEmpty()? ACC_FINAL : 0);
			case INTERFACE, ANNOTATION -> ACC_ABSTRACT|ACC_INTERFACE;
			case ENUM -> ACC_SUPER|ACC_FINAL|ACC_ENUM;
		};
		
		
		var signature = makeSignature(extension, interfaces, typeArgs);
		
		String[] interfaceStrings;
		if(interfaces.isEmpty()) interfaceStrings = null;
		else{
			interfaceStrings = new String[interfaces.size()];
			for(int i = 0; i<interfaces.size(); i++){
				interfaceStrings[i] = interfaces.get(i).raw().slashed();
			}
		}
		
		writer.visit(V19, accessFlags, name.slashed(), signature, extension.raw().slashed(), interfaceStrings);
		for(var permit : permits){
			writer.visitPermittedSubclass(permit.slashed());
		}
		writeAnnotations(anns, writer::visitAnnotation);
	}
	
	private static String makeSignature(GenericType extension, List<GenericType> interfaces, Map<ClassName, GenericType> typeArgs){
		int len = extension.jvmSignatureLen();
		if(!typeArgs.isEmpty()){
			len += 2;
			for(var e : typeArgs.entrySet()){
				len += e.getKey().any().length() + 1 + e.getValue().jvmSignatureLen();
			}
		}
		for(var interf : interfaces){
			len += interf.jvmSignatureLen();
		}
		
		var signature = new StringBuilder(len);
		
		if(!typeArgs.isEmpty()){
			signature.append('<');
			for(var e : typeArgs.entrySet()){
				signature.append(e.getKey()).append(':');
				e.getValue().jvmSignature(signature);
			}
			signature.append('>');
		}
		extension.jvmSignature(signature);
		for(var interf : interfaces){
			interf.jvmSignature(signature);
		}
		
		assert signature.length() == len : signature.length() + " " + len;
		return signature.toString();
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
						init.getArgOp("arg" + i);
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
		var arrType = new GenericType(name, Optional.empty(), 1, List.of());
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
			
			fun.getStaticOp(name, "$VALUES");
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
				fun.setStaticOp(name, field.name());// Enum.NAME=obj
				fun.setElementOP();
			}
			
			fun.setStaticOp(name, "$VALUES");
			fun.end();
		}
	}
	
	public void defineField(Visibility visibility, Set<Access> accesses, Collection<AnnGen> annotations, JType type, String name) throws MalformedJorth{
		checkEnd();
		if(fields.containsKey(name)) throw new MalformedJorth("Field " + name + " already exists");
		fields.put(name, new FieldGen(this.name, visibility, name, type,
		                              Collections.unmodifiableSet(accesses.isEmpty()?
		                                                          EnumSet.noneOf(Access.class) :
		                                                          EnumSet.copyOf(accesses))));
		
		
		var descriptor = type.jvmDescriptorStr();
		var signature  = type.jvmSignatureStr();
		
		var access       = visibility.flag|accesses.stream().mapToInt(f -> f.flag).reduce((a, b) -> a|b).orElse(0);
		var fieldVisitor = writer.visitField(access, name, descriptor, signature, null);
		
		writeAnnotations(annotations, fieldVisitor::visitAnnotation);
		
		fieldVisitor.visitEnd();
	}
	
	public static void writeAnnotations(Collection<AnnGen> annotations, BiFunction<String, Boolean, AnnotationVisitor> visitor){
		for(var ann : annotations){
			ClassName           annType = ann.type();
			Map<String, Object> args    = ann.args();
			
			var annWriter = visitor.apply(new GenericType(annType).jvmDescriptorStr(), true);
			for(var e : args.entrySet()){
				var argName  = e.getKey();
				var argValue = e.getValue();
				if(argValue.getClass().isArray()){
					var arrAnn = annWriter.visitArray(argName);
					for(int i = 0; i<Array.getLength(argValue); i++){
						arrAnn.visit(null, Array.get(argValue, i));
					}
					arrAnn.visitEnd();
					
				}else if(argValue instanceof Enum<?> eVal){
					annWriter.visitEnum(argName, GenericType.of(eVal.getClass()).jvmSignatureStr(), eVal.name());
				}else{
					annWriter.visit(argName, argValue);
				}
				
			}
			
			annWriter.visitEnd();
		}
	}
	
	public FunctionGen defineFunction(String name, Visibility visibility, Set<Access> access, JType returnType, Collection<FunctionGen.ArgInfo> args, List<AnnGen> anns) throws MalformedJorth{
		checkEnd();
		if(name.equals("<init>") && accessSet.contains(Access.STATIC)){
			throw new MalformedJorth("Static class can not be instantiated");
		}
		var fun = new FunctionGen(this, name, visibility, access, returnType, args, anns);
		
		var argStr = new ArrayList<JType>(args.size());
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
	public boolean isPrimitive(){
		return false;
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
		throw new MalformedJorth("Function of " + signature + " does not exist in " + name.dotted());
	}
	@Override
	public Stream<? extends FunctionInfo> getFunctionsByName(String name){
		return functions.values().stream().filter(f -> f.name().equals(name));
	}
	@Override
	public Stream<? extends FunctionInfo> getFunctions(){
		return functions.values().stream();
	}
	
	@Override
	public String toString(){
		return name.toString();
	}
	
	@Override
	public List<GenericType> interfaces(){
		return interfaces;
	}
	@Override
	public List<Enum<?>> enumConstantNames(){
		if(type != ClassType.ENUM) return List.of();
		throw new NotImplementedException();//TODO
	}
}
