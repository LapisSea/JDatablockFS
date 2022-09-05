package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOTypeDB;
import com.lapissea.cfs.type.TypeDef;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.access.AnnotatedType;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IODynamic;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.JorthWriter;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.ConsoleColors.*;

public final class TemplateClassLoader extends ClassLoader{
	
	private record TypeNamed(String name, TypeDef def){}
	
	private static final Map<TypeNamed, byte[]> CLASS_DATA_CACHE=Collections.synchronizedMap(new WeakValueHashMap<>());
	
	private static final boolean PRINT_GENERATING_INFO=GlobalConfig.configFlag("classGen.printGeneratingInfo", false);
	private static final boolean PRINT_BYTECODE       =GlobalConfig.configFlag("classGen.printBytecode", false);
	private static final boolean EXIT_ON_FAIL         =GlobalConfig.configFlag("classGen.exitOnFail", false);
	
	private final IOTypeDB db;
	
	public TemplateClassLoader(IOTypeDB db, ClassLoader parent){
		super(TemplateClassLoader.class.getSimpleName()+"{"+db+"}", parent);
		this.db=db;
	}
	
	@Override
	protected Class<?> findClass(String className) throws ClassNotFoundException{
		TypeDef def=getDef(className);
		if(def.isUnmanaged()){
			throw new UnsupportedOperationException(className+" is unmanaged! All unmanaged types must be present! Unmanaged types may contain mechanism not understood by the base IO engine.");
		}
		
		if(!def.isIoInstance()&&!def.isEnum()){
			throw new UnsupportedOperationException("Can not generate: "+className+". It is not an "+IOInstance.class.getSimpleName()+" or Enum");
		}
		
		var classData=CLASS_DATA_CACHE.get(new TypeNamed(className, def));
		if(classData==null){
			var typ=new TypeNamed(className, def.clone());
			
			var hash=hashCode();
			Log.trace("Generating template: {} - {}", className, (Supplier<String>)()->{
				var cols=List.of(BLACK, RED, GREEN, YELLOW, BLUE, PURPLE, CYAN, WHITE);
				return cols.get((int)(Integer.toUnsignedLong(hash)%cols.size()))+Integer.toHexString(hash)+" "+RESET;
			});
			
			try{
				classData=jorthGenerate(typ, def.isIoInstance()?this::generateIOInstance:this::generateEnum);
			}catch(Throwable e){
				if(EXIT_ON_FAIL){
					e.printStackTrace();
					System.exit(-1);
				}
				throw e;
			}
			CLASS_DATA_CACHE.put(typ, classData);
		}
		
		return defineClass(className, ByteBuffer.wrap(classData), null);
	}
	
	private byte[] jorthGenerate(TypeNamed classType, UnsafeBiConsumer<TypeNamed, JorthWriter, MalformedJorthException> generator){
		if(PRINT_GENERATING_INFO) LogUtil.println("generating", "\n"+TextUtil.toTable(classType.name, classType.def.getFields()));
		
		var jorth=new JorthCompiler(this);
		
		try(var writer=jorth.writeCode()){
			generator.accept(classType, writer);
		}catch(MalformedJorthException e){
			throw new RuntimeException("Failed to generate class "+classType.name, e);
		}
		
		byte[] byt;
		try{
			byt=jorth.classBytecode(PRINT_BYTECODE);
		}catch(MalformedJorthException e){
			throw new RuntimeException(e);
		}
		return byt;
	}
	
	private void generateEnum(TypeNamed classType, JorthWriter writer) throws MalformedJorthException{
		writer.write("#TOKEN(0) className define", classType.name);
		writer.write(
			"""
				public visibility
				className enum start
				"""
		);
		for(var constant : classType.def.getEnumConstants()){
			writer.write(constant.getName()).write("enum constant");
		}
	}
	
	private void generateIOInstance(TypeNamed classType, JorthWriter writer) throws MalformedJorthException{
		
		writer.write("#TOKEN(0) genClassName      define", classType.name);
		writer.write("#TOKEN(0) IOInstance.Def    define", IOInstance.Def.class.getName());
		writer.write("#TOKEN(0) IOValue           define", IOValue.class.getName());
		writer.write("#TOKEN(0) IONullability     define", IONullability.class.getName());
		writer.write("#TOKEN(0) IODynamic         define", IODynamic.class.getName());
		writer.write("#TOKEN(0) IODependency      define", IODependency.class.getName());
		writer.write("#TOKEN(0) IODependency      define", IODependency.class.getName());
		writer.write("#TOKEN(0) IOValue.Reference define", IOValue.Reference.class.getName());
		writer.write("#TOKEN(0) IOValue.Unsigned  define", IOValue.Unsigned.class.getName());
		
		writer.write(
			"""
				[genClassName] IOInstance.Def implements
				public visibility
				genClassName interface start
				"""
		);
		
		for(var field : classType.def.getFields()){
			var type=toJorthGeneric(field.getType());
			
			if(IONullability.NullLogic.canHave(new AnnotatedType.Simple(
				field.isDynamic()?List.of(IOFieldTools.makeAnnotation(IODynamic.class)):List.of(),
				field.getType().getTypeClass(db)
			))){
				writer.write("{#TOKEN(0)} IONullability @", field.getNullability().toString());
			}
			if(field.isDynamic()){
				writer.write("IODynamic @");
			}
			if(field.isUnsigned()){
				writer.write("IOValue.Unsigned @");
			}
			if(field.getReferenceType()!=null){
				writer.write("{#TOKEN(0) dataPipeType} IOValue.Reference @", field.getReferenceType().toString());
			}
			if(!field.getDependencies().isEmpty()){
				writer.write("{#RAW(0)} IODependency @", field.getDependencies().stream().collect(Collectors.joining(" ", "[", "]")));
			}
			
			writer.write(
				"""
					#RAW(0) returns
					#TOKEN(1) function
					""",
				type,
				field.getName());
		}
	}
	
	private String toJorthGeneric(TypeLink typ){
		StringBuilder sb=new StringBuilder();
		if(typ.argCount()>0){
			sb.append("[");
			for(int i=0;i<typ.argCount();i++){
				var arg=typ.arg(i);
				sb.append(toJorthGeneric(arg)).append(" ");
			}
			sb.append("] ");
		}
		if(typ.getTypeName().startsWith("[")){
			var nam=typ.getTypeName();
			while(nam.startsWith("[")){
				sb.append("array ");
				nam=nam.substring(1);
			}
			sb.append(switch(nam){
				case "B" -> "byte";
				case "S" -> "short";
				case "I" -> "int";
				case "J" -> "long";
				case "F" -> "float";
				case "D" -> "double";
				case "C" -> "char";
				case "Z" -> "boolean";
				default -> {
					if(!nam.startsWith("L")||!nam.endsWith(";")) throw new NotImplementedException("Unknown tyoe: "+nam);
					yield nam.substring(1, nam.length()-1);
				}
			});
			return sb.toString();
		}
		sb.append(typ.getTypeName());
		return sb.toString();
	}
	
	private TypeDef getDef(String name) throws ClassNotFoundException{
		TypeDef def;
		try{
			def=db.getDefinitionFromClassName(name);
		}catch(IOException e){
			throw new RuntimeException("Failed to fetch data from database", e);
		}
		if(def==null){
			throw new ClassNotFoundException(name+" is not defined in database");
		}
		return def;
	}
}
