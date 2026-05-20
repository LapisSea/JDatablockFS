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

public class ClassDefinition{
	
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
	private      ClassType  type;
	private      AccessSet  access;
	private      Visibility visibility;
	public final TypeSource typeSource;
	
	private final Map<FunctionInfo.Signature, FunctionDefinition> functions = new LinkedHashMap<>();
	private final Map<String, FieldDefinition>                    fields    = new LinkedHashMap<>();
	
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
			return makeArr(type);
		}
		return Optional.of(getClassInfo());
	}
	private Optional<ClassInfo> makeArr(GenericType type){
		try{
			return Optional.of(new ClassInfo.OfArray(typeSource, type.withDims(type.dims() - 1)));
		}catch(MalformedJorth e){
			throw new RuntimeException(e);
		}
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
				throw NotImplementedException.infer();//TODO: implement .getFunction()
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
		
		if(functions.values().stream().noneMatch(e -> e.name().equals("<init>"))){
			try{
				instanceInit().body().callSuper();
			}catch(MalformedJorth e){
				throw new RuntimeException(e);
			}
		}
		
		
		var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
		
		visitClass(writer);
		
		for(FieldDefinition f : fields.values()){
			f.visit(writer);
		}
		
		for(FunctionDefinition value : functions.values()){
			value.visit(writer);
		}
		writer.visitEnd();
		return writer.toByteArray();
	}
	
	private void visitClass(ClassWriter writer){
		int accessFlags = visibility.flag|switch(type){
			case CLASS -> ACC_SUPER|(permits.isEmpty()? ACC_FINAL : 0);
			case INTERFACE, ANNOTATION -> ACC_ABSTRACT|ACC_INTERFACE;
			case ENUM -> ACC_SUPER|ACC_FINAL|ACC_ENUM;
		};
		
		var signature = makeSignature(extension, interfaces, Map.of());
		
		
		String[] interfaceStrings;
		if(interfaces.isEmpty()) interfaceStrings = null;
		else{
			interfaceStrings = new String[interfaces.size()];
			for(int i = 0; i<interfaces.size(); i++){
				interfaceStrings[i] = interfaces.get(i).raw().slashed();
			}
		}
		
		writer.visit(V19, accessFlags, name.slashed(), signature, extension.raw().slashed(), interfaceStrings);
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
	
	public void start(ClassName name, ClassType type, AccessSet access, Visibility visibility){
		this.name = Objects.requireNonNull(name);
		this.type = Objects.requireNonNull(type);
		this.access = Objects.requireNonNull(access);
		this.visibility = Objects.requireNonNull(visibility);
	}
	
	
	FunctionDefinition getFunction(FunctionInfo.Signature signature){
		return functions.get(signature);
	}
	void updateSignature(FunctionInfo.Signature oldSignature, FunctionInfo.Signature signature, FunctionDefinition caller){
		if(!functions.containsKey(signature)){
			functions.put(signature, caller);
		}
		if(oldSignature != null){
			functions.remove(oldSignature);
		}
	}
	
	public FunctionDefinition staticInit(){
		return function("<clinit>").access(AccessSet.STATIC).visibility(Visibility.PUBLIC);
	}
	public FunctionDefinition instanceInit(){
		return function("<init>").visibility(Visibility.PUBLIC);
	}
	public FunctionDefinition function(String name){
		return new FunctionDefinition(this, name);
	}
	
	private List<JType> toJTypes(List<ArgInfo> args){
		var res = new ArrayList<JType>(args.size());
		for(var arg : args){
			res.add(arg.type());
		}
		return res;
	}
	
	public FieldDefinition field(String name, Class<?> type){
		return field(name, GenericType.of(type));
	}
	public FieldDefinition field(String name, JType type){
		return fields.computeIfAbsent(name, n -> new FieldDefinition(this, n, type));
	}
	
	public ClassDefinition permits(ClassName name) throws MalformedJorth{
		if(!permits.add(name)){
			throw new MalformedJorth(name.dotted() + " already permitted");
		}
		return this;
	}
	public ClassDefinition implement(GenericType interfaceSig) throws MalformedJorth{
		interfaces.add(interfaceSig);
		return this;
	}
	
	public ClassDefinition extendsType(GenericType type){
		extension = Objects.requireNonNull(type);
		return this;
	}
	
	public ClassName name(){ return name; }
	public ClassInfo superType() throws MalformedJorth{
		return typeSource.byName(extension.raw());
	}
}
