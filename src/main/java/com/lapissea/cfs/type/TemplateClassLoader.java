package com.lapissea.cfs.type;

import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;

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
		TypeDefinition typeDefinition=getDef(className);
		if(!typeDefinition.isIoInstance()){
			throw new UnsupportedOperationException("Can not generate: "+className+". It is not an "+IOInstance.class.getSimpleName());
		}
		
		LogUtil.println("generating", className, "from", typeDefinition);
		
		var jorth=new JorthCompiler(this);
		
		try(var writer=jorth.writeCode()){
			
			writer.write(
				"""
					public visibility
					#TOKEN(0) class start
					""",
				className
			);
			
			if(true)throw new NotImplementedException();
			
		}catch(MalformedJorthException e){
			throw new RuntimeException("Failed to generate class "+className, e);
		}
		
		try{
			return defineClass(className, ByteBuffer.wrap(jorth.classBytecode()), null);
		}catch(MalformedJorthException e){
			throw new RuntimeException(e);
		}
	}
	
	private TypeDefinition getDef(String name) throws ClassNotFoundException{
		TypeDefinition def;
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
