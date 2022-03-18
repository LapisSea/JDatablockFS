package com.lapissea.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class Dummy extends IOInstance<Dummy>{
	
	private static int COUNT=0;
	
	static Dummy first(){return new Dummy(COUNT=123);}
	static Dummy auto() {return new Dummy(++COUNT);}
	
	@IOValue
	public int val;
	
	public Dummy(){}
	public Dummy(int val){
		this.val=val;
	}
}
