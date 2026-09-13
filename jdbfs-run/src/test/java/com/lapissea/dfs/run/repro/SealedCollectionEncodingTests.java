package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.CollectionAdapter;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the production sealed-element size estimate against serialization and round-trip reads.
 */
public class SealedCollectionEncodingTests{
	
	public abstract static sealed class Element extends IOInstance.Managed<Element> permits First, Second{ }
	
	public static final class First extends Element{
		@IOValue
		public int value;
		public First()         { }
		public First(int value){ this.value = value; }
	}
	
	public static final class Second extends Element{
		@IOValue
		public long value;
		public Second()          { }
		public Second(long value){ this.value = value; }
	}
	
	@org.testng.annotations.DataProvider
	public Object[][] sealedIds(){
		return new Object[][]{
			{1}, {127}, {128}, {255}, {256},
			{32767}, {32768}, {65535}, {65536},
			{8388607}, {8388608}, {16777215}, {16777216}, {Integer.MAX_VALUE}
		};
	}
	
	@Test(dataProvider = "sealedIds")
	public void registeredElementSizeMatchesEncoding(int id) throws Exception{
		var provider = providerStartingAt(id);
		assertThat(provider.getTypeDb().toID(Element.class, elementType(First.class), true)).isEqualTo(id);
		assertEncoding(provider, new First(0x12345678), id);
	}
	
	@Test(dataProvider = "sealedIds")
	public void unregisteredElementSizeMatchesEncoding(int id) throws Exception{
		var provider = providerStartingAt(id);
		assertThat(provider.getTypeDb().fromID(Element.class, id)).isNull();
		assertEncoding(provider, new First(0x12345678), id);
	}
	
	@Test
	public void nullElementUsesOnlyNullMarker() throws IOException{
		var provider = DataProvider.newVerySimpleProvider();
		var io       = elementIO();
		var out      = new ContentOutputBuilder();
		assertThat(io.calcByteSize(provider, null)).isEqualTo(1);
		io.write(provider, out, null);
		assertThat(out.size()).isEqualTo(1);
		try(var input = new ContentInputStream.BA(out.toByteArray())){
			assertThat(io.read(provider, input, null)).isNull();
			assertThat(input.read()).isEqualTo(-1);
		}
		assertThat(provider.getTypeDb().fromID(Element.class, 1)).isNull();
	}
	
	@org.testng.annotations.DataProvider
	public Object[][] registrationBoundaries(){
		return new Object[][]{{255}, {65535}, {16777215}};
	}
	
	@Test(dataProvider = "registrationBoundaries")
	public void sizingBeforeWritingAccountsForDistinctNewSubtypes(int firstId) throws Exception{
		var  provider  = providerStartingAt(firstId);
		var  io        = elementIO();
		var  elements  = Arrays.<Element>asList(new First(42), new Second(123456789L), null, new First(7));
		long estimated = 0;
		for(var element : elements) estimated += io.calcByteSize(provider, element);
		
		var out     = new ContentOutputBuilder();
		var adapter = new CollectionAdapter.OfList<>(io);
		adapter.write(elements, provider, out);
		assertThat(estimated).as("collection estimate across ID boundary %d", firstId).isEqualTo(out.size());
		assertThat(provider.getTypeDb().toID(Element.class, elementType(First.class), false)).isEqualTo(firstId);
		assertThat(provider.getTypeDb().toID(Element.class, elementType(Second.class), false)).isEqualTo(firstId + 1);
		try(var input = new ContentInputStream.BA(out.toByteArray())){
			assertThat(adapter.read(elements.size(), provider, input, null)).isEqualTo(elements);
			assertThat(input.read()).isEqualTo(-1);
		}
	}
	
	private static void assertEncoding(DataProvider provider, Element element, int expectedId) throws IOException{
		var  io        = elementIO();
		long estimated = io.calcByteSize(provider, element);
		var  out       = new ContentOutputBuilder();
		io.write(provider, out, element);
		assertThat(estimated).as("estimated bytes for sealed ID %d", expectedId).isEqualTo(out.size());
		assertThat(io.calcByteSize(provider, element)).as("estimate after registration").isEqualTo(estimated);
		try(var input = new ContentInputStream.BA(out.toByteArray())){
			assertThat(input.readUnsignedInt4Dynamic()).as("encoded sealed ID").isEqualTo(expectedId);
		}
		try(var input = new ContentInputStream.BA(out.toByteArray())){
			assertThat(io.read(provider, input, null)).isEqualTo(element);
			assertThat(input.read()).as("no trailing bytes").isEqualTo(-1);
		}
	}
	
	private static CollectionAdapter.ElementIOImpl.SealedTypeImpl<Element> elementIO(){
		var universe = SealedUtil.getSealedUniverse(Element.class, false).orElseThrow();
		return new CollectionAdapter.ElementIOImpl.SealedTypeImpl<>(SealedUtil.SealedInstanceUniverse.of(universe).orElseThrow());
	}
	
	@SuppressWarnings("unchecked")
	private static Class<Element> elementType(Class<? extends Element> type){
		return (Class<Element>)type;
	}
	
	private static DataProvider providerStartingAt(int id) throws ReflectiveOperationException{
		var db = new IOTypeDB.MemoryOnlyDB.Basic();
		// Seed only the next ID, avoiding millions of artificial subtype registrations.
		// The test module already opens this package for reflective fixtures.
		db.fromID(Element.class, 1);
		var multiverseField = IOTypeDB.MemoryOnlyDB.Basic.class.getDeclaredField("sealedMultiverse");
		multiverseField.setAccessible(true);
		var universe     = ((Map<?, ?>)multiverseField.get(db)).get(Element.class);
		var counterField = universe.getClass().getDeclaredField("idCounter");
		counterField.setAccessible(true);
		counterField.setInt(universe, id);
		return new DataProvider.VerySimple(MemoryData.empty()){
			@Override
			public IOTypeDB getTypeDb(){ return db; }
		};
	}
}
