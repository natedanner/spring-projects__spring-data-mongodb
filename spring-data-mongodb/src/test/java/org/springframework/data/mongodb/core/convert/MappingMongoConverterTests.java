/*
 * Copyright 2018-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.core.convert;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions.MongoConverterConfigurationAdapter;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.test.util.Client;
import org.springframework.data.mongodb.test.util.MongoClientExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Integration tests for {@link MappingMongoConverter}.
 *
 * @author Christoph Strobl
 */
@ExtendWith(MongoClientExtension.class)
public class MappingMongoConverterTests {

	private static final String DATABASE = "mapping-converter-tests";

	private static @Client MongoClient client;

	private MongoDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, DATABASE);

	private MappingMongoConverter converter;
	private MongoMappingContext mappingContext;
	private DbRefResolver dbRefResolver;

	@BeforeEach
	void setUp() {

		MongoDatabase database = client.getDatabase(DATABASE);

		database.getCollection("samples").deleteMany(new Document());
		database.getCollection("java-time-types").deleteMany(new Document());

		dbRefResolver = spy(new DefaultDbRefResolver(factory));

		mappingContext = new MongoMappingContext();
		mappingContext.setSimpleTypeHolder(new MongoCustomConversions(Collections.emptyList()).getSimpleTypeHolder());
		mappingContext.setInitialEntitySet(new HashSet<>(
				Arrays.asList(WithLazyDBRefAsConstructorArg.class, WithLazyDBRef.class, WithJavaTimeTypes.class)));
		mappingContext.setAutoIndexCreation(false);
		mappingContext.afterPropertiesSet();

		converter = new MappingMongoConverter(dbRefResolver, mappingContext);
		converter.afterPropertiesSet();
	}

	@Test // DATAMONGO-2004
	void resolvesLazyDBRefOnAccess() {

		client.getDatabase(DATABASE).getCollection("samples")
				.insertMany(Arrays.asList(new Document("_id", "sample-1").append("value", "one"),
						new Document("_id", "sample-2").append("value", "two")));

		Document source = new Document("_id", "id-1").append("lazyList",
				Arrays.asList(new com.mongodb.DBRef("samples", "sample-1"), new com.mongodb.DBRef("samples", "sample-2")));

		WithLazyDBRef target = converter.read(WithLazyDBRef.class, source);

		verify(dbRefResolver).resolveDbRef(any(), isNull(), any(), any());

		assertThat(target.lazyList).isInstanceOf(LazyLoadingProxy.class);
		assertThat(target.getLazyList()).contains(new Sample("sample-1", "one"), new Sample("sample-2", "two"));

		verify(dbRefResolver).bulkFetch(any());
	}

	@Test // GH-4312
	void conversionShouldAllowReadingAlreadyResolvedReferences() {

		Document sampleSource = new Document("_id", "sample-1").append("value", "one");
		Document source = new Document("_id", "id-1").append("sample", sampleSource);

		WithSingleValueDbRef read = converter.read(WithSingleValueDbRef.class, source);

		assertThat(read.sample).isEqualTo(converter.read(Sample.class, sampleSource));
		verifyNoInteractions(dbRefResolver);
	}

	@Test // GH-4312
	void conversionShouldAllowReadingAlreadyResolvedListOfReferences() {

		Document sample1Source = new Document("_id", "sample-1").append("value", "one");
		Document sample2Source = new Document("_id", "sample-2").append("value", "two");
		Document source = new Document("_id", "id-1").append("lazyList", List.of(sample1Source, sample2Source));

		WithLazyDBRef read = converter.read(WithLazyDBRef.class, source);

		assertThat(read.lazyList).containsExactly(converter.read(Sample.class, sample1Source),
				converter.read(Sample.class, sample2Source));
		verifyNoInteractions(dbRefResolver);
	}

	@Test // GH-4312
	void conversionShouldAllowReadingAlreadyResolvedMapOfReferences() {

		Document sample1Source = new Document("_id", "sample-1").append("value", "one");
		Document sample2Source = new Document("_id", "sample-2").append("value", "two");
		Document source = new Document("_id", "id-1").append("sampleMap",
				new Document("s1", sample1Source).append("s2", sample2Source));

		WithMapValueDbRef read = converter.read(WithMapValueDbRef.class, source);

		assertThat(read.sampleMap) //
				.containsEntry("s1", converter.read(Sample.class, sample1Source)) //
				.containsEntry("s2", converter.read(Sample.class, sample2Source));
		verifyNoInteractions(dbRefResolver);
	}

	@Test // GH-4312
	void conversionShouldAllowReadingAlreadyResolvedMapOfLazyReferences() {

		Document sample1Source = new Document("_id", "sample-1").append("value", "one");
		Document sample2Source = new Document("_id", "sample-2").append("value", "two");
		Document source = new Document("_id", "id-1").append("sampleMapLazy",
				new Document("s1", sample1Source).append("s2", sample2Source));

		WithMapValueDbRef read = converter.read(WithMapValueDbRef.class, source);

		assertThat(read.sampleMapLazy) //
				.containsEntry("s1", converter.read(Sample.class, sample1Source)) //
				.containsEntry("s2", converter.read(Sample.class, sample2Source));
		verifyNoInteractions(dbRefResolver);
	}

	@Test // GH-4312
	void resolvesLazyDBRefMapOnAccess() {

		client.getDatabase(DATABASE).getCollection("samples")
				.insertMany(Arrays.asList(new Document("_id", "sample-1").append("value", "one"),
						new Document("_id", "sample-2").append("value", "two")));

		Document source = new Document("_id", "id-1").append("sampleMapLazy",
				new Document("s1", new com.mongodb.DBRef("samples", "sample-1")).append("s2",
						new com.mongodb.DBRef("samples", "sample-2")));

		WithMapValueDbRef target = converter.read(WithMapValueDbRef.class, source);

		verify(dbRefResolver).resolveDbRef(any(), isNull(), any(), any());

		assertThat(target.sampleMapLazy).isInstanceOf(LazyLoadingProxy.class);
		assertThat(target.getSampleMapLazy()).containsEntry("s1", new Sample("sample-1", "one")).containsEntry("s2",
				new Sample("sample-2", "two"));

		verify(dbRefResolver).bulkFetch(any());
	}

	@Test // GH-4312
	void conversionShouldAllowReadingAlreadyResolvedLazyReferences() {

		Document sampleSource = new Document("_id", "sample-1").append("value", "one");
		Document source = new Document("_id", "id-1").append("sampleLazy", sampleSource);

		WithSingleValueDbRef read = converter.read(WithSingleValueDbRef.class, source);

		assertThat(read.sampleLazy).isEqualTo(converter.read(Sample.class, sampleSource));
		verifyNoInteractions(dbRefResolver);
	}

	@Test // DATAMONGO-2004
	void resolvesLazyDBRefConstructorArgOnAccess() {

		client.getDatabase(DATABASE).getCollection("samples")
				.insertMany(Arrays.asList(new Document("_id", "sample-1").append("value", "one"),
						new Document("_id", "sample-2").append("value", "two")));

		Document source = new Document("_id", "id-1").append("lazyList",
				Arrays.asList(new com.mongodb.DBRef("samples", "sample-1"), new com.mongodb.DBRef("samples", "sample-2")));

		WithLazyDBRefAsConstructorArg target = converter.read(WithLazyDBRefAsConstructorArg.class, source);

		verify(dbRefResolver).resolveDbRef(any(), isNull(), any(), any());

		assertThat(target.lazyList).isInstanceOf(LazyLoadingProxy.class);
		assertThat(target.getLazyList()).contains(new Sample("sample-1", "one"), new Sample("sample-2", "two"));

		verify(dbRefResolver).bulkFetch(any());
	}

	@Test // DATAMONGO-2400
	void readJavaTimeValuesWrittenViaCodec() {

		configureConverterWithNativeJavaTimeCodec();
		MongoCollection<Document> mongoCollection = client.getDatabase(DATABASE).getCollection("java-time-types");

		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		WithJavaTimeTypes source = WithJavaTimeTypes.withJavaTimeTypes(now);
		source.id = "id-1";

		mongoCollection.insertOne(source.toDocument());

		assertThat(converter.read(WithJavaTimeTypes.class, mongoCollection.find(new Document("_id", source.id)).first()))
				.isEqualTo(source);
	}

	void configureConverterWithNativeJavaTimeCodec() {

		converter = new MappingMongoConverter(dbRefResolver, mappingContext);
		converter.setCustomConversions(
				MongoCustomConversions.create(MongoConverterConfigurationAdapter::useNativeDriverJavaTimeCodecs));
		converter.afterPropertiesSet();
	}

	public static class WithLazyDBRef {

		@Id String id;
		@DBRef(lazy = true) List<Sample> lazyList;

		List<Sample> getLazyList() {
			return lazyList;
		}
	}

	@Data
	public static class WithSingleValueDbRef {

		@Id //
		String id;

		@DBRef //
		Sample sample;

		@DBRef(lazy = true) //
		Sample sampleLazy;
	}

	@Data
	public static class WithMapValueDbRef {

		@Id String id;

		@DBRef //
		Map<String, Sample> sampleMap;

		@DBRef(lazy = true) //
		Map<String, Sample> sampleMapLazy;
	}

	public static class WithLazyDBRefAsConstructorArg {

		@Id String id;
		@DBRef(lazy = true) List<Sample> lazyList;

		public WithLazyDBRefAsConstructorArg(String id, List<Sample> lazyList) {

			this.id = id;
			this.lazyList = lazyList;
		}

		List<Sample> getLazyList() {
			return lazyList;
		}
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class Sample {

		@Id String id;
		String value;
	}

	@Data
	static class WithJavaTimeTypes {

		@Id String id;
		LocalDate localDate;
		LocalTime localTime;
		LocalDateTime localDateTime;

		static WithJavaTimeTypes withJavaTimeTypes(Instant instant) {

			WithJavaTimeTypes instance = new WithJavaTimeTypes();

			instance.localDate = LocalDate.from(instant.atZone(ZoneId.of("CET")));
			instance.localTime = LocalTime.from(instant.atZone(ZoneId.of("CET")));
			instance.localDateTime = LocalDateTime.from(instant.atZone(ZoneId.of("CET")));

			return instance;
		}

		Document toDocument() {
			return new Document("_id", id).append("localDate", localDate).append("localTime", localTime)
					.append("localDateTime", localDateTime);
		}
	}
}
