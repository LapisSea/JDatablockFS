package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.Endable;
import com.lapissea.util.LogUtil;

import java.util.List;

public class FunctionGen implements Endable{
	public record Arg(GenericType type, String name){}
	
	private final String      functionName;
	private final GenericType returns;
	private final List<Arg>   args;
	
	public FunctionGen(String functionName, GenericType returns, List<Arg> args){
		this.functionName=functionName;
		this.returns=returns;
		this.args=args;
	}
	
	@Override
	public void end() throws MalformedJorthException{
		LogUtil.print("fun end");
	}
}
