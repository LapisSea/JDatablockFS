package com.lapissea.jorth;

import java.io.Writer;
import java.util.List;

/**
 * Purely used for generating "known good" ASM code
 */
public class WTFIsMyBytecode<ARG>{
	
	static class InnerStaticClass{
		int hi;
	}
	
	byte[] bArr;
	
	List<String> strs;
	
	ARG arg;
	
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
	
	public int compareTo(ARG o){
		return 0;
	}
	public String cast(Object o){
		var a = (String)o;
		var b = (String[])o;
		var c = (List<String>)o;
		return a;
	}
}
