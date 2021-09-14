package com.lapisseqa.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class Dummy extends IOInstance<Dummy>{
	
	private static int COUNT=0;
	
	static Dummy first(){return new Dummy(COUNT=0);}
	static Dummy auto() {return new Dummy(COUNT++);}
	
	@IOValue
	int dummyValue;
	
	public Dummy(){}
	public Dummy(int dummyValue){
		this.dummyValue=dummyValue;
	}
}
