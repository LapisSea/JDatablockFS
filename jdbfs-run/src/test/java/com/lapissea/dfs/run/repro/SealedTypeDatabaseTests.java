package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.type.IOTypeDB;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class SealedTypeDatabaseTests{
	sealed interface Shape{
		final class Circle implements Shape{ }
		final class Square implements Shape{ }
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int toId(IOTypeDB db, Class<?> type, boolean record) throws IOException{
		return db.toID((Class)Shape.class, (Class)type, record);
	}

	@Test
	public void basicLookupPreviewsWithoutRegistering() throws IOException{
		var db = new IOTypeDB.MemoryOnlyDB.Basic();
		int preview = toId(db, Shape.Circle.class, false);
		assertThat(preview).isPositive();
		assertThat(db.fromID(Shape.class, preview)).isNull();
		assertThat(toId(db, Shape.Square.class, false)).isEqualTo(preview);
		int circle = toId(db, Shape.Circle.class, true);
		assertThat(circle).isEqualTo(preview);
		assertThat(toId(db, Shape.Circle.class, false)).isEqualTo(circle);
		assertThat(db.fromID(Shape.class, circle)).isEqualTo(Shape.Circle.class);

		int square = toId(db, Shape.Square.class, false);
		assertThat(square).isGreaterThan(circle);
		assertThat(db.fromID(Shape.class, square)).isNull();
		assertThat(toId(db, Shape.Square.class, true)).isEqualTo(square);
		assertThat(db.fromID(Shape.class, square)).isEqualTo(Shape.Square.class);
	}

	@Test
	public void bakedUniverseRegistersMissingSubtypeOnlyWhenRequested() throws IOException{
		var basic = new IOTypeDB.MemoryOnlyDB.Basic();
		int circle = toId(basic, Shape.Circle.class, true);
		var fixed = basic.bake();
		assertThat(toId(fixed, Shape.Circle.class, false)).isEqualTo(circle);
		assertThat(toId(fixed, Shape.Square.class, false)).isEqualTo(-1);
		assertThat(fixed.fromID(Shape.class, circle + 1)).isNull();
		int square = toId(fixed, Shape.Square.class, true);
		assertThat(square).isGreaterThan(circle);
		assertThat(toId(fixed, Shape.Square.class, false)).isEqualTo(square);
		assertThat(fixed.fromID(Shape.class, square)).isEqualTo(Shape.Square.class);
		assertThat(fixed.fromID(Shape.class, circle)).isEqualTo(Shape.Circle.class);
	}

	@Test
	public void bakedUniverseResolvesRegisteredIdsAndRejectsUnknownIds() throws IOException{
		var basic = new IOTypeDB.MemoryOnlyDB.Basic();
		int circle = toId(basic, Shape.Circle.class, true);
		int square = toId(basic, Shape.Square.class, true);
		var fixed = basic.bake();
		assertThat(fixed.fromID(Shape.class, circle)).isEqualTo(Shape.Circle.class);
		assertThat(fixed.fromID(Shape.class, square)).isEqualTo(Shape.Square.class);
		for(int id : new int[]{Integer.MIN_VALUE, -1, 0, square + 1, square + 2, Integer.MAX_VALUE}){
			assertThat(fixed.fromID(Shape.class, id)).as("unknown sealed ID %d", id).isNull();
		}
	}
}

