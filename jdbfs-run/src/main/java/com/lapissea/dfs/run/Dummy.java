package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

public class Dummy extends IOInstance.Managed<Dummy>{
	
	private static int COUNT = 0;
	
	static Dummy first(){ return new Dummy(COUNT = 123); }
	static Dummy auto() { return new Dummy(++COUNT); }
	
	@IOValue
	public int val;
	
	public Dummy(){ }
	public Dummy(int val){
		this.val = val;
	}
}
