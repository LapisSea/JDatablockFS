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
			
			writer.write(
				"""
					[#TOKEN(0)] #TOKEN(1) extends
					public visibility
					#TOKEN(0) class start
					""",
				className, IOInstance.class.getName()
			);
			
			for(var field : def.getFields()){
				
				writer.write(
					"""
						private visibility
						#TOKEN(0) @
						#RAW(1) #TOKEN(2) field
						
//						#RAW(1) returns
//						#TOKEN(3) function start
//							this #TOKEN(2) get
//						end
						""",
					IOValue.class.getName(),
					toJorthGeneric(field.getType()),
					field.getName(),
					"get"+TextUtil.firstToUpperCase(field.getName()));
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
					if(!nam.startsWith("L")||!nam.endsWith(";"))throw new NotImplementedException("Unknown tyoe: "+nam);
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
