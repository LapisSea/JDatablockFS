package com.lapissea.jorth;

import java.io.Writer;
import java.util.List;

public class WTFIsMyBytecode{
	
	List<String> strs;
	
	static{
		int a = 0;
	}
	
	static boolean ayy(Object a, Object b){
		return a == b;
	}
	
	
	public WTFIsMyBytecode(){
		int     a = 0, b = 0;
		boolean y = a == b;
	}
	
	public String foo;
	
	public String getFoo(){
		return foo;
	}
	
	void reg(List<Writer> arg)            { }
	void upper(List<? extends Writer> arg){ }
	void lower(List<? super Writer> arg)  { }
	void wild(List<?> arg)                { }
}
