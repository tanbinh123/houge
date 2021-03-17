package top.yein.tethys.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.javafaker.Faker;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.powermock.reflect.Whitebox;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import top.yein.tethys.domain.CachedJwtSecret;
import top.yein.tethys.entity.JwtSecret;

/**
 * {@link JwtSecretDaoImpl} 单元测试.
 *
 * @author KK (kzou227@qq.com)
 */
class JwtSecretDaoImplTest extends AbstractTestDao {

  private Faker faker = new Faker(Locale.SIMPLIFIED_CHINESE);

  private JwtSecretDao newJwtSecretRepository() {
    return new JwtSecretDaoImpl(r2dbcClient);
  }

  @Test
  void insert() {
    var dao = newJwtSecretRepository();
    var entity = new JwtSecret();
    entity.setId(faker.random().hex());
    entity.setAlgorithm("HS512");
    entity.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var p = dao.insert(entity);
    StepVerifier.create(p).expectNext(1).expectComplete().verify();

    StepVerifier.create(
            r2dbcClient
                .sql("select * from jwt_secrets where id=$1")
                .bind(0, entity.getId())
                .fetch()
                .one())
        .consumeNextWith(
            dbRow ->
                assertSoftly(
                    s -> {
                      s.assertThat(dbRow.get("id")).as("id").isEqualTo(entity.getId());
                      s.assertThat(dbRow.get("algorithm"))
                          .as("algorithm")
                          .isEqualTo(entity.getAlgorithm());
                      s.assertThat(dbRow.get("secret_key"))
                          .as("secret_key")
                          .isEqualTo(entity.getSecretKey());
                      s.assertThat(dbRow.get("deleted")).as("deleted").isEqualTo(0);
                      s.assertThat(dbRow.get("create_time")).as("create_time").isNotNull();
                      s.assertThat(dbRow.get("update_time")).as("update_time").isNotNull();
                    }))
        .expectComplete()
        .verify();

    // 清理数据
    clean("delete from jwt_secrets where id=$1", new Object[] {entity.getId()});
  }

  @Test
  void delete() {
    var dao = newJwtSecretRepository();
    var entity = new JwtSecret();
    entity.setId(faker.random().hex());
    entity.setAlgorithm("HS512");
    entity.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var p = dao.insert(entity).then(dao.delete(entity.getId()));
    StepVerifier.create(p).expectNext(1).expectComplete().verify();
  }

  @Test
  void findById() {
    var dao = newJwtSecretRepository();
    var entity = new JwtSecret();
    entity.setId(faker.random().hex());
    entity.setAlgorithm("HS512");
    entity.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var p = dao.insert(entity).then(dao.findById(entity.getId()));
    StepVerifier.create(p)
        .consumeNextWith(
            dbRow -> {
              assertSoftly(
                  s -> {
                    s.assertThat(dbRow.getId()).as("id").isEqualTo(entity.getId());
                    s.assertThat(dbRow.getAlgorithm())
                        .as("algorithm")
                        .isEqualTo(entity.getAlgorithm());
                    s.assertThat(dbRow.getSecretKey())
                        .as("secret_key")
                        .isEqualTo(entity.getSecretKey());
                    s.assertThat(dbRow.getDeleted()).as("deleted").isEqualTo(0);
                    s.assertThat(dbRow.getCreateTime()).as("create_time").isNotNull();
                    s.assertThat(dbRow.getUpdateTime()).as("update_time").isNotNull();
                  });
            })
        .expectComplete()
        .verify();

    // 清理数据
    clean("delete from jwt_secrets where id=$1", new Object[] {entity.getId()});
  }

  @Test
  void findAll() {
    var dao = newJwtSecretRepository();
    var entity1 = new JwtSecret();
    entity1.setId(faker.random().hex());
    entity1.setAlgorithm("HS512");
    entity1.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var entity2 = new JwtSecret();
    entity2.setId(faker.random().hex());
    entity2.setAlgorithm("HS512");
    entity2.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var p = dao.insert(entity1).then(dao.insert(entity2)).thenMany(dao.findAll());
    StepVerifier.create(p)
        .recordWith(ArrayList::new)
        .thenConsumeWhile(unused -> true)
        .consumeRecordedWith(
            jwtSecrets ->
                assertThat(jwtSecrets).extracting("id").contains(entity1.getId(), entity2.getId()))
        .expectComplete()
        .verify();

    // 清理数据
    clean(
        "delete from jwt_secrets where id in ($1,$2)",
        new Object[] {entity1.getId(), entity2.getId()});
  }

  @Test
  void refreshAll() {
    var dao = newJwtSecretRepository();
    AsyncCache<String, CachedJwtSecret> jwtSecretCache =
        Whitebox.getInternalState(dao, "jwtSecretCache");

    var entity1 = new JwtSecret();
    entity1.setId(faker.random().hex());
    entity1.setAlgorithm("HS512");
    entity1.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var entity2 = new JwtSecret();
    entity2.setId(faker.random().hex());
    entity2.setAlgorithm("HS512");
    entity2.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var p = dao.insert(entity1).then(dao.insert(entity2)).thenMany(dao.refreshAll());
    var cachedJwtSecrets = new ArrayList<CachedJwtSecret>();
    StepVerifier.create(p)
        .recordWith(() -> cachedJwtSecrets)
        .thenConsumeWhile(unused -> true)
        .expectComplete()
        .verify();

    // 校验缓存中的对象
    var syncCache = jwtSecretCache.synchronous();
    for (CachedJwtSecret cachedJwtSecret : cachedJwtSecrets) {
      assertThat(cachedJwtSecret).isEqualTo(syncCache.getIfPresent(cachedJwtSecret.getId()));
    }

    // 清理数据
    clean(
        "delete from jwt_secrets where id in ($1,$2)",
        new Object[] {entity1.getId(), entity2.getId()});
  }

  @Test
  void loadById() {
    var dao = newJwtSecretRepository();
    var entity = new JwtSecret();
    entity.setId(faker.random().hex());
    entity.setAlgorithm("HS512");
    entity.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var loadByIdPublishers = new ArrayList<Publisher<CachedJwtSecret>>();
    for (int i = 0; i < 10; i++) {
      loadByIdPublishers.add(dao.loadById(entity.getId()));
    }

    var p = dao.insert(entity).then(Flux.concat(loadByIdPublishers).collectList());
    StepVerifier.create(p)
        .consumeNextWith(
            cachedJwtSecrets -> {
              System.out.println(cachedJwtSecrets);
              assertThat(cachedJwtSecrets)
                  .allMatch(
                      cachedJwtSecret ->
                          cachedJwtSecret == cachedJwtSecrets.stream().findAny().get());
            })
        .expectComplete()
        .verify();

    // 清理数据
    clean("delete from jwt_secrets where id in ($1)", new Object[] {entity.getId()});
  }

  @Test
  void loadNoDeleted() {
    var dao = newJwtSecretRepository();
    AsyncCache<String, CachedJwtSecret> jwtSecretCache =
        Whitebox.getInternalState(dao, "jwtSecretCache");

    var entity1 = new JwtSecret();
    entity1.setId(faker.random().hex());
    entity1.setAlgorithm("HS512");
    entity1.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));

    var entity2 = new JwtSecret();
    entity2.setId(faker.random().hex());
    entity2.setAlgorithm("HS512");
    entity2.setSecretKey(ByteBuffer.wrap(faker.random().hex(256).getBytes(StandardCharsets.UTF_8)));
    var p = dao.insert(entity1).then(dao.insert(entity2)).thenMany(dao.loadNoDeleted());
    StepVerifier.create(p)
        .recordWith(ArrayList::new)
        .thenConsumeWhile(unused -> true)
        .consumeRecordedWith(
            cachedJwtSecrets -> {
              assertThat(cachedJwtSecrets)
                  .extracting("id")
                  .contains(entity1.getId(), entity2.getId());
            })
        .expectComplete()
        .verify();

    // 清理数据
    clean(
        "delete from jwt_secrets where id in ($1,$2)",
        new Object[] {entity1.getId(), entity2.getId()});
    //    clean(
    //        "delete from jwt_secrets where id in ($1)",
    //        new Object[] {new String[] {entity1.getId(), entity2.getId()}});
  }
}