package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.util.function.BiFunction;

@IOInstance.Def.Order({"from", "to"})
@IOInstance.Def.ToString.Format("{@from - @to}")
public interface IORange extends IOInstance.Def<IORange>{
	@IOValue.Unsigned
	@IODependency.VirtualNumSize
	long from();
	@IODependency.VirtualNumSize
	@IOValue.Unsigned
	long to();
	
	static IORange of(long from, long to){
		class Ctor{
			private static final BiFunction<Long, Long, IORange> VAL =
				Def.constrRef(IORange.class, long.class, long.class);
		}
		return Ctor.VAL.apply(from, to);
	}
}
