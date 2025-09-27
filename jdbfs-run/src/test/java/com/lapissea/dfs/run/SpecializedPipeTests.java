package com.lapissea.dfs.run;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

public class SpecializedPipeTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	@BeforeClass
	void before(){ StandardStructPipe.of(ObjectID.BID.class, StructPipe.STATE_IO_FIELD); }
	
	private static final Annotation UNSIGNED = Annotations.make(IOValue.Unsigned.class);
	
	record FieldDef(
		Class<?> type, boolean getter, boolean setter, List<Annotation> annotations,
		Function<RandomGenerator, Object> generator
	){
		FieldDef withGetter(boolean getter){ return new FieldDef(type, getter, setter, annotations, generator); }
		FieldDef withSetter(boolean setter){ return new FieldDef(type, getter, setter, annotations, generator); }
		FieldDef withAnnotations(List<Annotation> ann){
			return new FieldDef(type, getter, setter, ann, generator);
		}
		FieldDef withGenerator(Function<RandomGenerator, Object> generator){
			return new FieldDef(type, getter, setter, annotations, generator);
		}
	}
	
	@org.testng.annotations.DataProvider
	Object[][] fieldVariations(){
		var base = Iters.of(new FieldDef(int.class, false, false, List.of(), RandomGenerator::nextInt));
		var getter = Iters.concat(
			base.map(e -> e.withGetter(true)),
			base.map(e -> e.withGetter(false))
		);
		var setter = Iters.concat(
			getter.map(e -> e.withSetter(true)),
			getter.map(e -> e.withSetter(false))
		);
		return Iters.concat(
			setter.map(e -> e.withAnnotations(List.of())),
			setter.map(e -> e.withAnnotations(List.of(UNSIGNED))
			                 .withGenerator(r -> r.nextInt(0, Integer.MAX_VALUE)))
		).map(e -> new Object[]{e}).toArray(Object[].class);
	}
	
	@SuppressWarnings("unchecked")
	@Test(dataProvider = "fieldVariations")
	<T1 extends IOInstance<T1>, T2 extends IOInstance<T2>> void testType(FieldDef field) throws IOException, LockedFlagSet{
		var basic   = (Class<T1>)makeFieldClass(field, false);
		var special = (Class<T2>)makeFieldClass(field, true);
		
		StructPipe<T1> basicPipe;
		StructPipe<T2> specialPipe;
		try(var ignore = ConfigDefs.DO_INTEGRITY_CHECK.temporarySet(false)){
			basicPipe = makeUncheckedPipe(basic);
			specialPipe = makeUncheckedPipe(special);
		}
		
		doIOTest(field, basicPipe, specialPipe);
		doIOTest(field, specialPipe, basicPipe);
	}
	private static <T1 extends IOInstance<T1>> StructPipe<T1> makeUncheckedPipe(Class<T1> basic){
		StructPipe<T1> pipe = StandardStructPipe.of(basic, StagedInit.STATE_DONE);
		if(pipe instanceof CheckedPipe.Standard<T1> ch){
			return ch.getUncheckedPipe();
		}
		return pipe;
	}
	
	
	@SuppressWarnings("unchecked")
	private static <TF extends IOInstance<TF>, TT extends IOInstance<TT>>
	void doIOTest(FieldDef field, StructPipe<TF> from, StructPipe<TT> to) throws IOException{
		TF  val = from.getType().make();
		var f1  = (IOField<TF, Object>)from.getType().getFields().byName("val").orElseThrow();
		var f2  = (IOField<TT, Object>)to.getType().getFields().byName("val").orElseThrow();
		f1.set(null, val, field.generator.apply(new RawRandom()));
		
		var ch = AllocateTicket.bytes(10).submit(DataProvider.newVerySimpleProvider());
		from.write(ch, val);
		TT read = to.readNew(ch, null);
		
		if(field.getter){
			assertThat(val).extracting("valGetCount").isEqualTo(1);
			assertThat(read).extracting("valGetCount").isEqualTo(0);
		}
		if(field.setter){
			assertThat(val).extracting("valSetCount").isEqualTo(1);
			assertThat(read).extracting("valSetCount").isEqualTo(1);
		}
		
		var val1 = f1.get(null, val);
		var val2 = f2.get(null, read);
		
		assertThat(val1).isEqualTo(val2);
	}
	
	private static Class<IOInstance<?>> makeFieldClass(FieldDef field, boolean special){
		var val = new TempClassGen.FieldGen(
			"val", TempClassGen.VisiblityGen.PUBLIC, false, field.type,
			Iters.concat1N(Annotations.make(IOValue.class), field.annotations).toList(),
			field.generator
		);
		var fields = new ArrayList<TempClassGen.FieldGen>();
		var fns    = new ArrayList<UnsafeConsumer<CodeStream, MalformedJorth>>();
		fields.add(val);
		if(field.getter){
			fields.add(new TempClassGen.FieldGen(
				"valGetCount", TempClassGen.VisiblityGen.PUBLIC, false, int.class,
				List.of(), null
			));
			fns.add(code -> code.write(
				"""
					@ {}
					public function getVal
						returns {}
					start
						get this valGetCount
						inc 1
						set this valGetCount
						get this val
						return
					end
					""",
				IOValue.class, field.type, Throwable.class
			));
		}
		if(field.setter){
			fields.add(new TempClassGen.FieldGen(
				"valSetCount", TempClassGen.VisiblityGen.PUBLIC, false, int.class,
				List.of(), null
			));
			fns.add(code -> code.write(
				"""
					@ {}
					public function setVal
						arg val {}
					start
						get this valSetCount
						inc 1
						set this valSetCount
						get #arg val
						set this val
					end
					""",
				IOValue.class, field.type
			));
		}
		var cg = new TempClassGen.ClassGen(
			"unnamed",
			fields,
			Set.of(new TempClassGen.CtorType.Empty()),
			IOInstance.Managed.class,
			special? List.of(Annotations.make(StructPipe.Special.class)) : List.of(),
			fns);
		return TempClassGen.gen(cg);
	}
}
