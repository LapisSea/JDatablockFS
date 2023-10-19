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
import java.util.stream.Collectors;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NOT_NULL;
import static org.testng.Assert.assertEquals;

public class GeneratedStructTests{
	
	@Test
	void basic(){
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = Struct.of(Dummy.class, StagedInit.STATE_DONE);
		assertEquals(Set.of("a", "b"), s.getFields().stream().map(IOField::getName).collect(Collectors.toSet()));
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
		
		@IOInstance.Def.ToString(name = false, fNames = false)
		@IOInstance.Def.Order({"a", "b"})
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = IOInstance.Def.of(Dummy.class, 69, NumberSize.SHORT);
		assertEquals("{69, SHORT}", s.toString());
	}
	
	@Test
	void orderedString(){
		
		@IOInstance.Def.ToString(name = false, fNames = false)
		@IOInstance.Def.Order({"b", "a"})
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = IOInstance.Def.of(Dummy.class, NumberSize.SHORT, 69);
		assertEquals("{SHORT, 69}", s.toString());
	}
	
	@Test
	void formatString(){
		@IOInstance.Def.ToString.Format("[!!className ]@a -> @b")
		@IOInstance.Def.Order({"a", "b"})
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
			NumberSize b();
		}
		
		var s = IOInstance.Def.of(Dummy.class, 69, NumberSize.SHORT);
		assertEquals("Dummy 69 -> SHORT", s.toString());
		assertEquals("69 -> SHORT", s.toShortString());
	}
	
	@Test(expectedExceptions = MalformedToStringFormat.class)
	void badFormat(){
		@IOInstance.Def.ToString.Format("@someField")
		interface Dummy extends IOInstance.Def<Dummy>{
			int a();
		}
		
		Struct.of(Dummy.class, StagedInit.STATE_DONE);
	}
	
}
