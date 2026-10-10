package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.exceptions.IllegalBitValue;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;

public class EnumFlagEncodingTests{
	
	public enum Big129{
		C000, C001, C002, C003, C004, C005, C006, C007, C008, C009, C010, C011, C012,
		C013, C014, C015, C016, C017, C018, C019, C020, C021, C022, C023, C024, C025,
		C026, C027, C028, C029, C030, C031, C032, C033, C034, C035, C036, C037, C038,
		C039, C040, C041, C042, C043, C044, C045, C046, C047, C048, C049, C050, C051,
		C052, C053, C054, C055, C056, C057, C058, C059, C060, C061, C062, C063, C064,
		C065, C066, C067, C068, C069, C070, C071, C072, C073, C074, C075, C076, C077,
		C078, C079, C080, C081, C082, C083, C084, C085, C086, C087, C088, C089, C090,
		C091, C092, C093, C094, C095, C096, C097, C098, C099, C100, C101, C102, C103,
		C104, C105, C106, C107, C108, C109, C110, C111, C112, C113, C114, C115, C116,
		C117, C118, C119, C120, C121, C122, C123, C124, C125, C126, C127, C128;
	}
	
	public enum Boundary128{
		D000, D001, D002, D003, D004, D005, D006, D007, D008, D009, D010, D011, D012,
		D013, D014, D015, D016, D017, D018, D019, D020, D021, D022, D023, D024, D025,
		D026, D027, D028, D029, D030, D031, D032, D033, D034, D035, D036, D037, D038,
		D039, D040, D041, D042, D043, D044, D045, D046, D047, D048, D049, D050, D051,
		D052, D053, D054, D055, D056, D057, D058, D059, D060, D061, D062, D063, D064,
		D065, D066, D067, D068, D069, D070, D071, D072, D073, D074, D075, D076, D077,
		D078, D079, D080, D081, D082, D083, D084, D085, D086, D087, D088, D089, D090,
		D091, D092, D093, D094, D095, D096, D097, D098, D099, D100, D101, D102, D103,
		D104, D105, D106, D107, D108, D109, D110, D111, D112, D113, D114, D115, D116,
		D117, D118, D119, D120, D121, D122, D123, D124, D125, D126, D127;
	}
	
	public enum Tiny16{
		T000, T001, T002, T003, T004, T005, T006, T007, T008, T009, T010, T011, T012,
		T013, T014, T015;
	}
	
	public enum Tiny2{A, B}
	
	public enum Boundary17{A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q}
	
	@DataProvider
	Object[][] universes(){
		return new Object[][]{
			{EnumUniverse.of(Tiny2.class)}, {EnumUniverse.of(Tiny16.class)},
			{EnumUniverse.of(Boundary17.class)}, {EnumUniverse.of(Boundary128.class)},
			{EnumUniverse.of(Big129.class)}
		};
	}
	
	@Test(dataProvider = "universes")
	public <T extends Enum<T>> void singleValueMatchesFlagEncoding(EnumUniverse<T> universe) throws IOException{
		for(var value : universe){
			var out = new ContentOutputBuilder();
			FlagWriter.writeSingle(out, universe, value);
			var expected = new ContentOutputBuilder();
			var flags    = new FlagWriter(NumberSize.BYTE);
			flags.writeEnum(universe, value);
			flags.fillRestAllOne().export(expected);
			Assert.assertEquals(out.toByteArray(), expected.toByteArray(), value.name());
			Assert.assertEquals(FlagReader.readSingle(new ContentInputStream.BA(out.toByteArray()), universe), value);
		}
	}
	
	@Test(dataProvider = "universes")
	public <T extends Enum<T>> void singleValueReadsFlagEncoding(EnumUniverse<T> universe) throws IOException{
		for(var value : universe){
			var out   = new ContentOutputBuilder();
			var flags = new FlagWriter(NumberSize.BYTE);
			flags.writeEnum(universe, value);
			flags.fillRestAllOne().export(out);
			Assert.assertEquals(FlagReader.readSingle(new ContentInputStream.BA(out.toByteArray()), universe), value);
		}
	}
	
	@Test(dataProvider = "universes")
	public <T extends Enum<T>> void unsetPaddingBitIsRejected(EnumUniverse<T> universe) throws IOException{
		var out   = new ContentOutputBuilder();
		var flags = new FlagWriter(NumberSize.BYTE);
		flags.writeEnum(universe, universe.getFirst());
		flags.fillRestAllOne().export(out);
		for(int bit = universe.bitSize; bit<Byte.SIZE; bit++){
			var data = out.toByteArray();
			data[0] &= ~(1<<bit);
			Assert.assertThrows(IllegalBitValue.class, () -> FlagReader.readSingle(new ContentInputStream.BA(data), universe));
		}
	}
	
}
