package com.lapissea.dfs.tools.utils;

import com.lapissea.dfs.type.BlacklistClassLoader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Predicate;

public class ToolUtils{
	
	public static void simulateMissingClasses(List<Predicate<String>> blacklist, Method make, Method use) throws Throwable{
		try{
			
			byte[] bb;
			{
				var clsl      = new BlacklistClassLoader(ToolUtils.class.getClassLoader(), List.of());
				var makeClass = clsl.loadClass(make.getDeclaringClass().getName());
				
				var m = makeClass.getDeclaredMethod(make.getName());
				m.setAccessible(true);
				bb = (byte[])m.invoke(null);
			}
			{
				var clsl     = new BlacklistClassLoader(ToolUtils.class.getClassLoader(), blacklist);
				var useClass = clsl.loadClass(use.getDeclaringClass().getName());
				
				var m = useClass.getDeclaredMethod(use.getName(), byte[].class);
				m.setAccessible(true);
				m.invoke(null, (Object)bb);
			}
		}catch(InvocationTargetException underlying){
			if(underlying.getCause() == null || underlying.getSuppressed().length>0){
				throw underlying;
			}
			throw underlying.getCause();
		}
	}
	
}
