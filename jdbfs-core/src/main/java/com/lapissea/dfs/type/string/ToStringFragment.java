package com.lapissea.dfs.type.string;

import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.List;
import java.util.Optional;

public sealed interface ToStringFragment{
	record NOOP() implements ToStringFragment{ }
	
	record Literal(String value) implements ToStringFragment{ }
	
	record Concat(List<ToStringFragment> fragments) implements ToStringFragment{ }
	
	record FieldValue(String name) implements ToStringFragment{ }
	
	record SpecialValue(SpecialValue.Value value) implements ToStringFragment{
		
		public static Optional<SpecialValue> of(String name){
			return Iters.from(SpecialValue.Value.values())
			            .firstMatching(v -> v.name.equalsIgnoreCase(name))
			            .map(SpecialValue::new);
		}
		
		public enum Value{
			CLASS_NAME("className");
			
			public final String name;
			Value(String name){
				this.name = name;
			}
		}
		
	}
	
	record OptionalBlock(ToStringFragment content) implements ToStringFragment{ }
	
	default IterablePP<ToStringFragment> deep(){
		return switch(this){
			case Concat c -> Iters.from(c.fragments).flatMap(ToStringFragment::deep);
			case OptionalBlock o -> o.content.deep();
			default -> Iters.of(this);
		};
	}
}
