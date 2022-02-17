package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.nio.ByteBuffer;

public class TemplateClassLoader extends ClassLoader{
	
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

//		LogUtil.println("generating", className, "from", "\n"+TextUtil.toTable(def.getFields()));
		
		var jorth=new JorthCompiler(this);
		
		try(var writer=jorth.writeCode()){
			
			writer.write("#TOKEN(0) genClassName define", className);
			writer.write("#TOKEN(0) IOInstance define", IOInstance.class.getName());
			writer.write("#TOKEN(0) IOValue define", IOValue.class.getName());
			
			writer.write(
				"""
					[genClassName] IOInstance extends
					public visibility
					genClassName class start
					"""
			);
			
			for(var field : def.getFields()){
				var type=toJorthGeneric(field.getType());
				
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
			throw new RuntimeException("Failed to generate class "+className, e);
		}
		
		try{
			var byt=jorth.classBytecode(false);
			return defineClass(className, ByteBuffer.wrap(byt), null);
		}catch(MalformedJorthException e){
			throw new RuntimeException(e);
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
			var e1=new ClassNotFoundException("Failed to fetch data from database");
			e1.addSuppressed(e);
			throw e1;
		}
		if(def==null){
			throw new ClassNotFoundException(name+" is not defined in database");
		}
		return def;
	}
}
