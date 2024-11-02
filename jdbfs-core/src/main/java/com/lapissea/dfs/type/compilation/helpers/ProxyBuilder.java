package com.lapissea.dfs.type.compilation.helpers;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;

/**
 * A class intended for internal use on types that have final field(s) and can not be directly written in to
 */
public abstract class ProxyBuilder<Actual extends IOInstance<Actual>> extends IOInstance.Managed<ProxyBuilder<Actual>>{
	
	public ProxyBuilder(){ }
	public ProxyBuilder(Struct<ProxyBuilder<Actual>> thisStruct){
		super(thisStruct);
	}
	
	public abstract Actual build();
	
	public void copyFrom(Actual value){
		var builderTyp = this.getThisStruct();
		var struct     = value.getThisStruct();
		
		var iter1 = struct.getFields().iterator();
		var iter2 = builderTyp.getFields().iterator();
		while(iter1.hasNext()){
			var e1 = iter1.next();
			//noinspection unchecked
			var e2 = (IOField<ProxyBuilder<Actual>, Object>)iter2.next();
			if(e1.isVirtual()) continue;
			e2.set(null, this, e1.get(null, value));
		}
	}
}
