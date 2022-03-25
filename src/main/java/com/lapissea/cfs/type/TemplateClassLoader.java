package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

public class TemplateClassLoader extends ClassLoader{
	
	private record TypeNamed(String name, TypeDef def){}
	
	private static final Map<TypeNamed, byte[]> CLASS_DATA_CACHE=Collections.synchronizedMap(new WeakValueHashMap<>());
	
	private static final boolean PRINT_GENERATING_INFO=UtilL.sysPropertyByClass(TemplateClassLoader.class, "PRINT_GENERATING_INFO", false, Boolean::parseBoolean);
	private static final boolean PRINT_BYTECODE       =UtilL.sysPropertyByClass(TemplateClassLoader.class, "PRINT_BYTECODE", false, Boolean::parseBoolean);
	
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
		if(!def.isIoInstance()){
			throw new UnsupportedOperationException("Can not generate: "+className+". It is not an "+IOInstance.class.getSimpleName());
		}
		
		var classData=CLASS_DATA_CACHE.get(new TypeNamed(className, def));
		if(classData==null){
			var typ=new TypeNamed(className, def.clone());
			classData=generateBytecode(typ);
			CLASS_DATA_CACHE.put(typ, classData);
		}
		
		return defineClass(className, ByteBuffer.wrap(classData), null);
	}
	
	private byte[] generateBytecode(TypeNamed classType){
		if(PRINT_GENERATING_INFO) LogUtil.println("generating", "\n"+TextUtil.toTable(classType.name, classType.def.getFields()));
		
		var jorth=new JorthCompiler(this);
		
		try(var writer=jorth.writeCode()){
			
			writer.write("#TOKEN(0) genClassName define", classType.name);
			writer.write("#TOKEN(0) IOInstance define", IOInstance.class.getName());
			writer.write("#TOKEN(0) IOValue define", IOValue.class.getName());
			writer.write("#TOKEN(0) IONullability define", IONullability.class.getName());
			writer.write("#TOKEN(0) IOType.Dynamic define", IOType.Dynamic.class.getName());
			writer.write("#TOKEN(0) IODependency define", IODependency.class.getName());
			
			writer.write(
				"""
					[genClassName] IOInstance extends
					public visibility
					genClassName class start
					"""
			);
			
			for(var field : classType.def.getFields()){
				var type=toJorthGeneric(field.getType());
				
				if(field.getNullability()!=null){
					writer.write("{#TOKEN(0)} IONullability @", field.getNullability().toString());
				}
				if(field.isDynamic()){
					writer.write("IOType.Dynamic @");
				}
				if(!field.getDependencies().isEmpty()){
					writer.write("{#RAW(0)} IODependency @", field.getDependencies().stream().collect(Collectors.joining(" ", "[", "]")));
				}
				
				writer.write(
					"""
						private visibility
						IOValue @
						#RAW(0) #TOKEN(1) field
						""",
					type,
					field.getName());
				
				writer.write(
					"""
						IOValue @
						public visibility
						#RAW(0) returns
						#TOKEN(1) function start
							this #TOKEN(2) get
						end
						""",
					type,
					"get"+TextUtil.firstToUpperCase(field.getName()),
					field.getName());
			}
			
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
