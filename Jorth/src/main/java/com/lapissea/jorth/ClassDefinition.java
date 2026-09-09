package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.FieldInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;
import org.objectweb.asm.ClassWriter;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.*;
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
	
	private Map<String, ClassName> typeDefinitions = new HashMap<>();
	
	public ClassDefinition(ClassLoader classLoader){
		typeSource = TypeSource.of(this::generatedClassInfo, classLoader == null? this.getClass().getClassLoader() : classLoader);
	}
	
	/**
	 * Builds bytecode intended for defineHiddenClass with NESTMATE using a lookup
	 * on definitionHost. The caller must use that definition context when loading.
	 */
	public static ClassDefinition hiddenNestmate(Class<?> definitionHost){
		return new ClassDefinition(definitionHost);
	}
	private ClassDefinition(Class<?> definitionHost){
		typeSource = TypeSource.ofNestmate(this::generatedClassInfo, Objects.requireNonNull(definitionHost));
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
				var n = fields.get(name);
				if(n == null){
					throw new MalformedJorth("Field " + name + " does not exist in " + ClassDefinition.this.name);
				}
				return n;
			}
			@Override
			public FunctionInfo getFunction(FunctionInfo.Signature signature) throws MalformedJorth{
				var method = functions.get(signature);
				if(method == null){
					if(getType() == ClassType.ENUM && signature.name().equals("<init>")){
						throw new MalformedJorth("Enum constructor does not exist: " + signature);
					}
					return typeSource.byType(extension).getFunction(signature);
				}
				return method;
			}
			@Override
			public Stream<? extends FunctionInfo> getFunctionsByName(String name){
				return Stream.concat(
					functions.values().stream().filter(f -> f.name().equals(name)),
					danglingFunctions.stream().filter(f -> f.name().equals(name) && !functions.containsKey(f.makeSignature()))
				);
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
				return type == ClassType.INTERFACE || type == ClassType.ANNOTATION;
			}
			@Override
			public boolean isFinal(){ return access.isFinal(); }
			@Override
			public boolean isPublic(){ return visibility == Visibility.PUBLIC; }
			@Override
			public List<GenericType> interfaces(){
				return Collections.unmodifiableList(interfaces);
			}
		};
	}
	
	
	public byte[] getClassFile() throws MalformedJorth{
		requireName();
		switch(type){
			case CLASS -> {
				ensureConstructor();
			}
			case INTERFACE -> { }
			case ENUM -> {
				ensureEnumConstructor();
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
		for(var permit : permits){
			writer.visitPermittedSubclass(permit.slashed());
		}
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
	public ClassDefinition genericArg(TypeVariable<?> tVar){
		var bounds = tVar.getBounds();
		if(bounds.length != 1){
			throw new NotImplementedException("Implement multi bound type variable");
		}
		return genericArg(bounds[0], tVar.getName());
	}
	public ClassDefinition genericArg(Type type, String name){
		return genericArg(GenericType.of(type), ClassName.dotted(name));
	}
	public ClassDefinition genericArg(GenericType type, ClassName name){
		if(typeArgs.put(Objects.requireNonNull(name), type.withTypeArgName(name)) != null){
			throw new IllegalArgumentException("Duplicate argument " + name);
		}
		return this;
	}
	public ClassDefinition type(ClassType type) throws MalformedJorth{
		if(this.type == ClassType.ENUM) throw new MalformedJorth("Can not change type from enum");
		if(access.isFinal() && !type.canBeFinal){
			throw new MalformedJorth("Can not make a final " + type);
		}
		this.type = Objects.requireNonNull(type);
		if(type.mustBeFinal){
			finalAcc();
		}
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
		var vals  = field(vType, "$VALUES").visibility(Visibility.PRIVATE).staticFinal();
		
		staticInit().body().lazyBlock(enumInit -> {
			var constants = fields.values().stream().filter(FieldDefinition::isEnumConstant).toList();
			
			for(int i = 0; i<constants.size(); i++){
				FieldDefinition constant = constants.get(i);
				try{
					constructEnum(enumInit, constant, i);
				}catch(Throwable e){
					throw new MalformedJorth("Failed to initialize enum constant: " + constant.name, e);
				}
			}
			
			enumInit.val(constants.size()).newObj(ClassName.slashed(vType.jvmDescriptorStr()));
			for(int i = 0; i<constants.size(); i++){
				enumInit.dup().val(i).get(constants.get(i)).setArrayElement();
			}
			enumInit.setField(vals);
		});
		
		function("values")
			.returns(vType)
			.staticAcc()
			.body()
			.get(vals)
			.call("clone")
			.cast(vType);
	}
	private void constructEnum(CodeBlock enumInit, FieldDefinition constant, int ordinal) throws MalformedJorth{
		enumInit.newObj(name(), b -> {
			b.val(constant.name).val(ordinal);
			constant.enumConstantInit.accept(b);
		}, true);
		enumInit.setField(constant);
	}
	
	
	private void ensureEnumConstructor() throws MalformedJorth{
		if(danglingFunctions.stream().anyMatch(f -> f.name().equals("<init>"))){
			throw new MalformedJorth("Define enum constructor bodies before constants");
		}
		if(functions.values().stream().noneMatch(f -> f.name().equals("<init>"))){
			instanceInit().body();
		}
	}
	void validateEnumConstructor(FunctionDefinition fn) throws MalformedJorth{
		if(fields.values().stream().anyMatch(FieldDefinition::isEnumConstant)){
			throw new MalformedJorth("Define enum constructors before constants");
		}
		if(functions.containsKey(fn.makeSignature())){
			throw new MalformedJorth("Duplicate enum constructor " + fn.makeSignature());
		}
		if(fn.visibility() != Visibility.PRIVATE || fn.isStatic() || fn.isFinal() || fn.returnType() != null || fn.isVarargs()){
			throw new MalformedJorth("Enum constructors must be private instance constructors without a return type or varargs");
		}
	}
	
	
	public ClassDefinition staticAcc(){
		return access(access.andStat());
	}
	public ClassDefinition finalAcc() throws MalformedJorth{
		if(!type.canBeFinal){
			throw new MalformedJorth("Can not make " + type + " final");
		}
		return access(access.andFin());
	}
	public ClassDefinition abstractAcc(){
		return access(access.andAbstr());
	}
	public AccessSet access(){
		return access;
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
		return function("<init>").visibility(getType() == ClassType.ENUM? Visibility.PRIVATE : Visibility.PUBLIC);
	}
	public FunctionDefinition function(String name){
		var res = new FunctionDefinition(this, name);
		danglingFunctions.add(res);
		return res;
	}
	
	public FieldDefinition field(Type type, String name){
		return field(JType.of(type), name);
	}
	public FieldDefinition field(JType type, String name){
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
	public ClassDefinition implement(Type interfaceSig) throws MalformedJorth{
		return implement(GenericType.of(interfaceSig));
	}
	public ClassDefinition implement(ClassName interfaceSig) throws MalformedJorth{
		return implement(GenericType.of(interfaceSig));
	}
	public ClassDefinition implement(GenericType interfaceSig) throws MalformedJorth{
		interfaces.add(interfaceSig);
		return this;
	}
	
	public ClassDefinition extendsType(Class<?> type){
		return extendsType(GenericType.of(type));
	}
	public ClassDefinition extendsType(ClassName type){
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
		return enumConstant(constantName, b -> { });
	}
	public FieldDefinition enumConstant(String constantName, CodeArg arguments) throws MalformedJorth{
		if(type != ClassType.ENUM) throw new MalformedJorth("Can not add enum constant on " + type);
		if(fields.containsKey(constantName)) throw new MalformedJorth("Duplicate enum constant " + constantName);
		Objects.requireNonNull(arguments);
		ensureEnumConstructor();
		var ordinal = (int)fields.values().stream().filter(FieldDefinition::isEnumConstant).count();
		var field   = new FieldDefinition(this, Objects.requireNonNull(constantName), new GenericType(name)).asEnumConstant(arguments);
		
		// Check if enum constructor is valid so it crashes right away
		var dummy = new CodeBlock(new TypeStack(null), typeSource, new FunctionDefinition(this, "<clinit>"));
		constructEnum(dummy, field, ordinal);
		
		fields.put(constantName, field);
		
		return field;
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
	
	public ClassName getTypeDef(String name){
		var res = typeDefinitions.get(name);
		if(res == null) throw new IllegalStateException("No type definition found with name " + name);
		return res;
	}
	public void typeDef(String name, Class<?> type){
		typeDef(name, ClassName.of(type));
	}
	public void typeDef(String name, ClassName type){
		var old = typeDefinitions.put(name, type);
		if(old != null){
			throw new IllegalArgumentException("Type definition " + name + " already defined");
		}
	}
	public ClassType getType(){
		return type;
	}
}
