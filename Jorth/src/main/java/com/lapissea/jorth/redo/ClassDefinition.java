package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.FieldInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;
import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

public class ClassDefinition extends AnnotationContainer<ClassDefinition>{
	
	static{
		Thread.startVirtualThread(() -> {
			try{
				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
				w.visit(V19, 0, "", "", "", null);
				w.visitField(0, "", "", null, null).visitEnd();
				var m = w.visitMethod(0, "", "()V", null, null);
				m.visitMaxs(0, 0);
				m.visitEnd();
				w.visitEnd();
				w.toByteArray();
			}catch(Throwable e){
				e.printStackTrace();
			}
		});
	}
	
	private      ClassName  name;
	private      ClassType  type       = ClassType.CLASS;
	private      AccessSet  access     = AccessSet.DEFAULT;
	private      Visibility visibility = Visibility.PUBLIC;
	public final TypeSource typeSource;
	
	private final List<FunctionDefinition>                        danglingFunctions = new ArrayList<>();
	private final Map<FunctionInfo.Signature, FunctionDefinition> functions         = new LinkedHashMap<>();
	private final Map<String, FieldDefinition>                    fields            = new LinkedHashMap<>();
	
	private final Map<ClassName, GenericType> typeArgs = new LinkedHashMap<>();
	
	private final Set<ClassName>    permits    = new LinkedHashSet<>();
	private final List<GenericType> interfaces = new ArrayList<>();
	private       GenericType       extension  = GenericType.OBJECT;
	
	public ClassDefinition(ClassLoader loader){
		var classLoader = loader == null? this.getClass().getClassLoader() : loader;
		typeSource = TypeSource.of(this::generatedClassInfo, classLoader);
	}
	
	private Optional<ClassInfo> generatedClassInfo(GenericType type){
		if(name == null) return Optional.empty();
		var raw = type.raw();
		if(!name.equals(raw)){
			return Optional.empty();
		}
		
		if(type.dims()>0){
			try{
				return Optional.of(new ClassInfo.OfArray(typeSource, type.withDims(type.dims() - 1)));
			}catch(MalformedJorth e){
				throw new RuntimeException(e);
			}
		}
		return Optional.of(getClassInfo());
	}
	
	private ClassInfo infoWrapper;
	public ClassInfo getClassInfo(){
		if(infoWrapper != null) return infoWrapper;
		return infoWrapper = new ClassInfo(){
			
			@Override
			public FieldInfo getField(String name) throws MalformedJorth{
				throw NotImplementedException.infer();//TODO: implement .getField()
			}
			@Override
			public FunctionInfo getFunction(FunctionInfo.Signature signature) throws MalformedJorth{
				var method = functions.get(signature);
				if(method == null){
					return typeSource.byType(extension).getFunction(signature);
				}
				return method;
			}
			@Override
			public Stream<? extends FunctionInfo> getFunctionsByName(String name){
				throw NotImplementedException.infer();//TODO: implement .getFunctionsByName()
			}
			@Override
			public Stream<? extends FunctionInfo> getFunctions(){
				throw NotImplementedException.infer();//TODO: implement .getFunctions()
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
			public boolean isInterface(){
				throw NotImplementedException.infer();//TODO: implement .isInterface()
			}
			@Override
			public boolean isFinal(){
				throw NotImplementedException.infer();//TODO: implement .isFinal()
			}
			@Override
			public List<GenericType> interfaces(){
				return Collections.unmodifiableList(interfaces);
			}
			@Override
			public List<Enum<?>> enumConstantNames(){
				throw NotImplementedException.infer();//TODO: implement .enumConstantNames()
			}
		};
	}
	
	
	public byte[] getClassFile() throws MalformedJorth{
		
		switch(type){
			case CLASS -> {
				ensureConstructor();
			}
			case INTERFACE -> { }
			case ENUM -> {
				enumValuesInit();
			}
			case ANNOTATION -> { }
		}
		
		
		var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
		
		visitClass(writer);
		
		for(FieldDefinition f : fields.values()){
			f.visit(writer);
		}
		
		for(FunctionDefinition value : functions.values()){
			value.visit(writer);
		}
		for(FunctionDefinition value : danglingFunctions){
			value.visit(writer);
		}
		writer.visitEnd();
		return writer.toByteArray();
	}
	
	private void ensureConstructor(){
		if(functions.values().stream().noneMatch(e -> e.name().equals("<init>"))){
			try{
				instanceInit().body().callSuperAutoPass();
			}catch(MalformedJorth e){
				throw new RuntimeException(e);
			}
		}
	}
	
	private void visitClass(ClassWriter writer){
		requireName();
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
		for(AnnotationDefinition annotation : annotations){
			annotation.visit(writer);
		}
	}
	private void requireName(){
		if(name == null) throw new IllegalStateException("Class name not defined");
	}
	
	private static String makeSignature(GenericType extension, List<GenericType> interfaces, Map<ClassName, GenericType> typeArgs){
		int len = extension.jvmSignatureLen();
		if(!typeArgs.isEmpty()){
			len += 2;
			for(var e : typeArgs.entrySet()){
				len += e.getKey().any().length() + 1 + e.getValue().withTypeArgName(Optional.empty()).jvmSignatureLen();
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
				e.getValue().withTypeArgName(Optional.empty()).jvmSignature(signature);
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
	
	public ClassDefinition name(ClassName className){
		this.name = Objects.requireNonNull(className);
		return this;
	}
	public ClassDefinition arg(Class<?> type, String name){
		return arg(GenericType.of(type), ClassName.dotted(name));
	}
	public ClassDefinition arg(GenericType type, ClassName name){
		if(typeArgs.put(Objects.requireNonNull(name), type.withTypeArgName(name)) != null){
			throw new IllegalArgumentException("Duplicate argument " + name);
		}
		return this;
	}
	public ClassDefinition type(ClassType type) throws MalformedJorth{
		if(this.type == ClassType.ENUM) throw new MalformedJorth("Can not change type from enum");
		this.type = Objects.requireNonNull(type);
		if(type == ClassType.ENUM) initEnum();
		return this;
	}
	
	private void initEnum() throws MalformedJorth{
		requireName();
		if(!extension.equals(GenericType.OBJECT)){
			throw new IllegalArgumentException("Enum classes can not explicitly extend a type");
		}
		extension = GenericType.of(Enum.class).withArgs(new GenericType(name));
		
		var vType = new GenericType(name).arrayType();
		var vals  = field("$VALUES", vType).visibility(Visibility.PRIVATE).staticFinal();
		
		instanceInit()
			.arg(String.class, "name")
			.arg(int.class, "ordinal")
			.body()
			.callSuperAutoPass();
		
		function("values")
			.returns(vType)
			.staticAcc()
			.body()
			.get(vals)
			.call("clone")
			.cast(vType);
	}
	
	
	private void enumValuesInit() throws MalformedJorth{
		var vType = new GenericType(name).arrayType();
		var fun   = staticInit().body();
		
		var constants = fields.values().stream().filter(FieldDefinition::isEnumConstant).toList();
		
		
		fun.val(constants.size());
		fun.newObj(ClassName.slashed(vType.jvmDescriptorStr()));
		
		for(int i1 = 0; i1<constants.size(); i1++){
			int i     = i1;
			var field = constants.get(i);
			if(!field.isEnumConstant()) continue;
			
			fun.dup();//array dup
			fun.val(i);//[i] = ...
			
			fun.newObj(name, e -> e.val(field.name).val(i));// new enum(name,ordinal)
			
			fun.dup();
			fun.set(field);// Enum.NAME=obj
			fun.setArrayElement();
		}
		
		fun.set(getField("$VALUES"));
	}
	
	public ClassDefinition access(AccessSet access){
		this.access = Objects.requireNonNull(access);
		return this;
	}
	public ClassDefinition visibility(Visibility visibility){
		this.visibility = Objects.requireNonNull(visibility);
		return this;
	}
	
	FunctionDefinition getFunction(FunctionInfo.Signature signature){
		requireName();
		var res = functions.get(signature);
		if(res != null) return res;
		return danglingFunctions.stream().filter(f -> f.makeSignature().equals(signature)).findFirst().orElse(null);
	}
	FunctionDefinition finalize(FunctionDefinition fn){
		var signature = fn.makeSignature();
		danglingFunctions.remove(fn);
		return functions.putIfAbsent(signature, fn);
	}
	
	public FunctionDefinition staticInit() throws MalformedJorth{
		var fn = function("<clinit>").access(AccessSet.STATIC).visibility(Visibility.PUBLIC);
		fn.body();
		return fn;
	}
	public FunctionDefinition instanceInit(){
		return function("<init>").visibility(Visibility.PUBLIC);
	}
	public FunctionDefinition function(String name){
		var res = new FunctionDefinition(this, name);
		danglingFunctions.add(res);
		return res;
	}
	
	public FieldDefinition field(String name, Class<?> type){
		return field(name, GenericType.of(type));
	}
	public FieldDefinition field(String name, JType type){
		requireName();
		return fields.computeIfAbsent(name, n -> new FieldDefinition(this, n, type));
	}
	
	public FieldDefinition getField(String name){
		var f = fields.get(name);
		if(f == null){
			throw new IllegalArgumentException("No field found with name " + name);
		}
		return f;
	}
	
	public ClassDefinition permits(ClassName name) throws MalformedJorth{
		if(!permits.add(name)){
			throw new MalformedJorth(name.dotted() + " already permitted");
		}
		return this;
	}
	public ClassDefinition implement(Class<?> interfaceSig) throws MalformedJorth{
		return implement(GenericType.of(interfaceSig));
	}
	public ClassDefinition implement(GenericType interfaceSig) throws MalformedJorth{
		interfaces.add(interfaceSig);
		return this;
	}
	
	public ClassDefinition extendsType(Class<?> type){
		return extendsType(GenericType.of(type));
	}
	public ClassDefinition extendsType(GenericType type){
		extension = Objects.requireNonNull(type);
		return this;
	}
	
	public ClassName name(){ return name; }
	public ClassInfo superType() throws MalformedJorth{
		return typeSource.byName(extension.raw());
	}
	
	public FieldDefinition enumConstant(String constantName) throws MalformedJorth{
		if(type != ClassType.ENUM) throw new MalformedJorth("Can not add enum constant on " + type);
		return field(constantName, new GenericType(name)).asEnumConstant();
	}
	public GenericType getArg(String name){
		return getArg(ClassName.dotted(name));
	}
	public GenericType getArg(ClassName name){
		var arg = typeArgs.get(name);
		if(arg == null){
			throw new IllegalArgumentException("No argument found with name " + name);
		}
		return arg;
	}
	public FunctionInfo getFunctionOverride(FunctionInfo.Signature signature) throws MalformedJorth{
		for(GenericType iType : interfaces){
			try{
				var info = typeSource.byType(iType);
				return info.getFunction(signature);
			}catch(MalformedJorth ignore){ }
		}
		var info = typeSource.byType(extension);
		return info.getFunction(signature);
	}
}
