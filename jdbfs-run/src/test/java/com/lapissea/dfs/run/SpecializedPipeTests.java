package com.lapissea.dfs.run;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

public class SpecializedPipeTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }

//	@BeforeClass
//	void before(){
//		StandardStructPipe.of(Chunk.class, StructPipe.STATE_IO_FIELD);
//	}
	
	private static final Annotation UNSIGNED = Annotations.make(IOValue.Unsigned.class);
	
	record FieldDef(
		Class<?> type, boolean getter, boolean setter, List<Annotation> annotations,
		Function<RandomGenerator, Object> generator
	){
		FieldDef(Class<?> type, Function<RandomGenerator, Object> generator){ this(type, false, false, List.of(), generator); }
		
		FieldDef withGetter(boolean getter)                                 { return new FieldDef(type, getter, setter, annotations, generator); }
		FieldDef withSetter(boolean setter)                                 { return new FieldDef(type, getter, setter, annotations, generator); }
		FieldDef withAnnotations(List<Annotation> ann){
			return new FieldDef(type, getter, setter, ann, generator);
		}
		FieldDef withNewAnnotations(Annotation... ann){
			return withAnnotations(Iters.concat(annotations, Iters.from(ann)).toList());
		}
		FieldDef withGenerator(Function<RandomGenerator, Object> generator){
			return new FieldDef(type, getter, setter, annotations, generator);
		}
		@Override
		public String toString(){
			var res = new StringJoiner(", ", type.getTypeName() + "{", "}");
			if(getter) res.add("withGet");
			if(setter) res.add("withSet");
			Iters.from(annotations).joinAsOptionalStr(", ", "anns: [", "]", ann -> ann.annotationType().getSimpleName()).ifPresent(res::add);
			return res.toString();
		}
	}
	
	@org.testng.annotations.DataProvider
	Object[][] fieldVariations(){
		
		IterablePP<FieldDef> doubles  = withFns(Iters.of(new FieldDef(double.class, RandomGenerator::nextDouble)));
		IterablePP<FieldDef> chars    = withFns(Iters.of(new FieldDef(char.class, r -> (char)r.nextInt(255))));
		IterablePP<FieldDef> floats   = withFns(Iters.of(new FieldDef(float.class, RandomGenerator::nextFloat)));
		IterablePP<FieldDef> longs    = withFns(Iters.of(new FieldDef(long.class, RandomGenerator::nextLong)));
		IterablePP<FieldDef> ints     = withFns(Iters.of(new FieldDef(int.class, RandomGenerator::nextInt)));
		IterablePP<FieldDef> shorts   = withFns(Iters.of(new FieldDef(short.class, r -> (short)r.nextInt())));
		IterablePP<FieldDef> bytes    = withFns(Iters.of(new FieldDef(byte.class, r -> (byte)r.nextInt())));
		IterablePP<FieldDef> booleans = withFns(Iters.of(new FieldDef(boolean.class, RandomGenerator::nextBoolean)));
		
		longs = withUnsigned(longs, r -> r.nextLong(0, Long.MAX_VALUE));
		ints = withUnsigned(ints, r -> r.nextInt(0, Integer.MAX_VALUE));
		shorts = withUnsigned(shorts, r -> (short)r.nextInt(0, Short.MAX_VALUE));
		
		doubles = withVirtualSize(doubles);
		chars = withVirtualSize(chars);
		floats = withVirtualSize(floats);
		longs = withVirtualSize(longs);
		ints = withVirtualSize(ints);
		shorts = withVirtualSize(shorts);
		
		return Iters.concat(
			doubles, chars, floats, longs, ints, shorts, bytes, booleans
		).flatMapArray(e -> new Object[][]{{e, true}, {e, false}}).toArray(Object[].class);
	}
	
	private static IterablePP<FieldDef> withFns(IterablePP<FieldDef> combinations){
		combinations = Iters.concat(
			combinations.map(e -> e.withGetter(true)),
			combinations.map(e -> e.withGetter(false))
		);
		combinations = Iters.concat(
			combinations.map(e -> e.withSetter(true)),
			combinations.map(e -> e.withSetter(false))
		);
		return combinations;
	}
	private static IterablePP<FieldDef> withUnsigned(IterablePP<FieldDef> combinations, Function<RandomGenerator, Object> generator){
		return Iters.concat(
			combinations.map(e -> e.withAnnotations(List.of())),
			combinations.map(e -> e.withAnnotations(List.of(UNSIGNED))
			                       .withGenerator(generator))
		);
	}
	private static IterablePP<FieldDef> withVirtualSize(IterablePP<FieldDef> combinations){
		return Iters.concat(
			combinations,
			combinations.map(e -> e.withNewAnnotations(Annotations.make(IODependency.VirtualNumSize.class))
			                       .withGenerator(r -> r.nextInt(0, Integer.MAX_VALUE)))
		);
	}
	
	@SuppressWarnings("unchecked")
	@Test(dataProvider = "fieldVariations")
	<T1 extends IOInstance<T1>, T2 extends IOInstance<T2>> void testType(FieldDef field, boolean imidiate) throws IOException, LockedFlagSet{
		var basic   = (Class<T1>)makeFieldClass(field, false);
		var special = (Class<T2>)makeFieldClass(field, true);
		
		StructPipe<T1> basicPipe;
		StructPipe<T2> specialPipe;
		try(var ignore = ConfigDefs.DO_INTEGRITY_CHECK.temporarySet(false)){
			basicPipe = makeUncheckedPipe(basic, imidiate);
			specialPipe = makeUncheckedPipe(special, imidiate);
		}
		assertThat(specialPipe).isInstanceOf(StructPipe.SpecializedImplementation.class);
		for(int i = 0; i<50; i++){
			doIOTest(field, basicPipe, specialPipe);
			doIOTest(field, specialPipe, basicPipe);
		}
	}
	private static <T1 extends IOInstance<T1>> StructPipe<T1> makeUncheckedPipe(Class<T1> basic, boolean imidiate){
		StructPipe<T1> pipe = imidiate? StandardStructPipe.of(basic, StagedInit.STATE_DONE) : StandardStructPipe.of(basic);
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
