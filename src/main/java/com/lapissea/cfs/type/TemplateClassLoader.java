package com.lapissea.cfs.type;

import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;

public class TemplateClassLoader extends ClassLoader{
	
	private final IOTypeDB db;
	
	public TemplateClassLoader(IOTypeDB db, ClassLoader parent){
		super(TemplateClassLoader.class.getSimpleName()+"{"+db+"}", parent);
		this.db=db;
	}
	
	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException{
		TypeDefinition def=getDef(name);
		if(!def.isIoInstance()){
			throw new UnsupportedOperationException("Can not generate: "+name+". It is not an "+IOInstance.class.getSimpleName());
		}
		
		LogUtil.println(def);
		
		//TODO: create class definition and generate it
		throw new NotImplementedException();
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
