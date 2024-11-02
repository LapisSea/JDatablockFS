package com.lapissea.dfs.run;

import com.lapissea.dfs.exceptions.MalformedToStringFormat;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IONullability;
import org.testng.annotations.Test;

import java.util.Set;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NOT_NULL;
import static org.assertj.core.api.Assertions.assertThat;

public class GeneratedStructTests{
	
	@Test
	void basic(){
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = Struct.of(Dummy.class, StagedInit.STATE_DONE);
		assertThat(s.getFields().mapped(IOField::getName)).containsExactlyInAnyOrder("a", "b");
	}
	
	@Test(expectedExceptions = NullPointerException.class)
	void nonNullableNullImplicit(){
		interface Dummy extends IOInstance.Def<Dummy>{
			ChunkPointer ptr();
		}
		
		IOInstance.Def.of(Dummy.class, (Object)null);
	}
	
	@Test(expectedExceptions = NullPointerException.class)
	void implicitNonNullableNull(){
		interface Dummy extends IOInstance.Def<Dummy>{
			@IONullability(NOT_NULL)
			String nonNullText();
		}
		
		IOInstance.Def.of(Dummy.class, (Object)null);
	}
	
	@Test(expectedExceptions = UnsupportedOperationException.class)
	void accessNonExistent(){
		interface Dummy extends IOInstance.Def<Dummy>{
			String a();
			String b();
		}
		
		Class<Dummy> cls  = IOInstance.Def.partialImplementation(Dummy.class, Set.of("a"));
		Dummy        inst = IOInstance.Def.of(cls);
		inst.b();
	}
	
	@Test
	void simpleString(){
		
		@IOInstance.StrFormat(name = false, fNames = false)
		@IOInstance.Order({"a", "b"})
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = IOInstance.Def.of(Dummy.class, 69, NumberSize.SHORT);
		assertThat(s).asString().isEqualTo("{69, SHORT}");
	}
	
	@Test
	void orderedString(){
		
		@IOInstance.StrFormat(name = false, fNames = false)
		@IOInstance.Order({"b", "a"})
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = IOInstance.Def.of(Dummy.class, NumberSize.SHORT, 69);
		assertThat(s).asString().isEqualTo("{SHORT, 69}");
	}
	
	@Test
	void formatString(){
		@IOInstance.StrFormat.Custom("[!!className ]@a -> @b")
		@IOInstance.Order({"a", "b"})
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = IOInstance.Def.of(Dummy.class, 69, NumberSize.SHORT);
		assertThat(s.toString()).asString().isEqualTo("Dummy 69 -> SHORT");
		assertThat(s.toShortString()).asString().isEqualTo("69 -> SHORT");
	}
	
	@Test(expectedExceptions = MalformedToStringFormat.class)
	void badFormat(){
		@IOInstance.StrFormat.Custom("@someField")
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
		}
		
		Dummy dummy = IOInstance.Def.of(Dummy.class, 1);
		dummy.toString();
	}
	
}
