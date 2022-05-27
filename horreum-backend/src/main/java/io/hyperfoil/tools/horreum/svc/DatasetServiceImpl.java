package io.hyperfoil.tools.horreum.svc;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;
import javax.annotation.security.PermitAll;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;

import org.hibernate.Hibernate;
import org.hibernate.query.NativeQuery;
import org.hibernate.transform.AliasToBeanResultTransformer;
import org.hibernate.type.IntegerType;
import org.hibernate.type.LongType;
import org.hibernate.type.TextType;
import org.hibernate.type.TimestampType;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladmihalcea.hibernate.type.json.JsonNodeBinaryType;

import io.hyperfoil.tools.horreum.api.DatasetService;
import io.hyperfoil.tools.horreum.api.QueryResult;
import io.hyperfoil.tools.horreum.entity.PersistentLog;
import io.hyperfoil.tools.horreum.entity.alerting.DatasetLog;
import io.hyperfoil.tools.horreum.entity.json.DataSet;
import io.hyperfoil.tools.horreum.entity.json.Label;
import io.hyperfoil.tools.horreum.entity.json.Test;
import io.hyperfoil.tools.horreum.server.WithRoles;
import io.hyperfoil.tools.horreum.server.WithToken;
import io.quarkus.runtime.Startup;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.eventbus.EventBus;

@ApplicationScoped
@Startup
public class DatasetServiceImpl implements DatasetService {
   private static final Logger log = Logger.getLogger(DatasetServiceImpl.class);

   //@formatter:off
   private static final String LABEL_QUERY =
         "WITH used_labels AS (" +
            "SELECT label.id AS label_id, label.name, ds.schema_id, count(le) AS count FROM dataset_schemas ds " +
            "JOIN label ON label.schema_id = ds.schema_id " +
            "LEFT JOIN label_extractors le ON le.label_id = label.id " +
            "WHERE ds.dataset_id = ?1 AND (?2 < 0 OR label.id = ?2) GROUP BY label.id, label.name, ds.schema_id" +
         "), lvalues AS (" +
            "SELECT ul.label_id, le.name, (CASE WHEN le.isarray THEN " +
                  "jsonb_path_query_array(dataset.data -> ds.index, le.jsonpath::::jsonpath) " +
               "ELSE " +
                  "jsonb_path_query_first(dataset.data -> ds.index, le.jsonpath::::jsonpath) " +
               "END) AS value " +
            "FROM dataset JOIN dataset_schemas ds ON dataset.id = ds.dataset_id " +
            "JOIN used_labels ul ON ul.schema_id = ds.schema_id " +
            "LEFT JOIN label_extractors le ON ul.label_id = le.label_id " +
            "WHERE dataset.id = ?1" +
         ") SELECT lvalues.label_id, ul.name, function, (CASE " +
               "WHEN ul.count > 1 THEN jsonb_object_agg(COALESCE(lvalues.name, ''), lvalues.value) " +
               "WHEN ul.count = 1 THEN jsonb_agg(lvalues.value) -> 0 " +
               "ELSE '{}'::::jsonb END" +
            ") AS value FROM label " +
            "JOIN lvalues ON lvalues.label_id = label.id " +
            "JOIN used_labels ul ON label.id = ul.label_id " +
            "GROUP BY lvalues.label_id, ul.name, function, ul.count";

   private static final String SCHEMAS_SELECT = "SELECT dataset_id, jsonb_agg(uri) as schemas FROM dataset_schemas ds JOIN dataset ON dataset.id = ds.dataset_id";
   private static final String DATASET_SUMMARY_SELECT = "SELECT ds.id, ds.runid AS runId, ds.ordinal, " +
         "ds.testid AS testId, test.name AS testname, ds.description, " +
         "EXTRACT(EPOCH FROM ds.start) * 1000 AS start, EXTRACT(EPOCH FROM ds.stop) * 1000 AS stop, " +
         "ds.owner, ds.access, dv.value AS view, schema_agg.schemas AS schemas " +
         "FROM dataset ds LEFT JOIN test ON test.id = ds.testid " +
         "LEFT JOIN schema_agg ON schema_agg.dataset_id = ds.id " +
         "LEFT JOIN dataset_view dv ON dv.dataset_id = ds.id AND dv.view_id = defaultview_id";
   private static final String LIST_TEST_DATASETS =
         "WITH schema_agg AS (" + SCHEMAS_SELECT + " WHERE testid = ?1 GROUP BY dataset_id" +
         ") " + DATASET_SUMMARY_SELECT + " WHERE testid = ?1";
   private static final String LIST_SCHEMA_DATASETS =
         "WITH ids AS (" +
            "SELECT dataset_id AS id FROM dataset_schemas WHERE uri = ?1" +
         "), schema_agg AS (" +
            SCHEMAS_SELECT + " WHERE dataset_id IN (SELECT id FROM ids) GROUP BY dataset_id" +
         ") " + DATASET_SUMMARY_SELECT + " WHERE ds.id IN (SELECT id FROM ids)";
   //@formatter:on
   protected static final AliasToBeanResultTransformer DATASET_SUMMARY_TRANSFORMER = new AliasToBeanResultTransformer(DatasetSummary.class);

   @Inject
   EntityManager em;

   @Inject
   SqlServiceImpl sqlService;

   @Inject
   TransactionManager tm;

   @Inject
   EventBus eventBus;

   // This is a nasty hack that will serialize all run -> dataset transformations and label calculations
   // The problem is that PostgreSQL's SSI will for some (unknown) reason rollback some transactions,
   // probably due to false sharing of locks. For some reason even using advisory locks in DB does not
   // solve the issue so we have to serialize this even outside the problematic transactions.
   private final ReentrantLock recalculationLock = new ReentrantLock();

   @PostConstruct
   void init() {
      sqlService.registerListener("calculate_labels", this::onLabelChanged);
   }

   @PermitAll
   @WithRoles
   @Override
   public DatasetService.DatasetList listTestDatasets(int testId, Integer limit, Integer page, String sort, String direction) {
      StringBuilder sql = new StringBuilder(LIST_TEST_DATASETS);
      // TODO: filtering by fingerprint
      addOrderAndPaging(limit, page, sort, direction, sql);
      Query query = em.createNativeQuery(sql.toString())
            .setParameter(1, testId);
      markAsSummaryList(query);
      DatasetService.DatasetList list = new DatasetService.DatasetList();
      //noinspection unchecked
      list.datasets = query.getResultList();
      list.total = DataSet.count("testid = ?1", testId);
      return list;
   }

   private void markAsSummaryList(Query query) {
      query.unwrap(NativeQuery.class)
            .addScalar("id", IntegerType.INSTANCE)
            .addScalar("runId", IntegerType.INSTANCE)
            .addScalar("ordinal", IntegerType.INSTANCE)
            .addScalar("testId", IntegerType.INSTANCE)
            .addScalar("testname", TextType.INSTANCE)
            .addScalar("description", TextType.INSTANCE)
            .addScalar("start", LongType.INSTANCE)
            .addScalar("stop", LongType.INSTANCE)
            .addScalar("owner", TextType.INSTANCE)
            .addScalar("access", IntegerType.INSTANCE)
            .addScalar("view", JsonNodeBinaryType.INSTANCE)
            .addScalar("schemas", JsonNodeBinaryType.INSTANCE)
            .setResultTransformer(DATASET_SUMMARY_TRANSFORMER);
   }

   private void addOrderAndPaging(Integer limit, Integer page, String sort, String direction, StringBuilder sql) {
      if (sort != null && sort.startsWith("view_data:")) {
         String[] parts = sort.split(":", 3);
         String vcid = parts[1];
         String label = parts[2];
         sql.append(" ORDER BY");
         // prefer numeric sort
         sql.append(" to_double(dv.value->'").append(vcid).append("'->>'").append(label).append("')");
         Util.addDirection(sql, direction);
         sql.append(", dv.value->'").append(vcid).append("'->>'").append(label).append("'");
         Util.addDirection(sql, direction);
      } else {
         Util.addOrderBy(sql, sort, direction);
      }
      Util.addLimitOffset(sql, limit, page);
   }

   @WithRoles
   @Override
   public QueryResult queryDataSet(Integer datasetId, String jsonpath, boolean array, String schemaUri) {
      if (schemaUri != null && schemaUri.isBlank()) {
         schemaUri = null;
      }
      QueryResult result = new QueryResult();
      result.jsonpath = jsonpath;
      try {
         if (schemaUri == null) {
            String func = array ? "jsonb_path_query_array" : "jsonb_path_query_first";
            String sqlQuery = "SELECT " + func + "(data, ?::::jsonpath)#>>'{}' FROM dataset WHERE id = ?";
            result.value = String.valueOf(Util.runQuery(em, sqlQuery, jsonpath, datasetId));
         } else {
            // This schema-aware query already assumes that DataSet.data is an array of objects with defined schema
            String schemaQuery = "jsonb_path_query(data, '$[*] ? (@.\"$schema\" == $schema)', ('{\"schema\":\"' || ? || '\"}')::::jsonb)";
            String sqlQuery;
            if (!array) {
               sqlQuery = "SELECT jsonb_path_query_first(" + schemaQuery + ", ?::::jsonpath)#>>'{}' FROM dataset WHERE id = ? LIMIT 1";
            } else {
               sqlQuery = "SELECT jsonb_agg(v)#>>'{}' FROM (SELECT jsonb_path_query(" + schemaQuery + ", ?::::jsonpath) AS v FROM dataset WHERE id = ?) AS values";
            }
            result.value = String.valueOf(Util.runQuery(em, sqlQuery, schemaUri, jsonpath, datasetId));
         }
         result.valid = true;
      } catch (PersistenceException pe) {
         SqlServiceImpl.setFromException(pe, result);
      }
      return result;
   }

   @WithRoles
   @Override
   public DatasetService.DatasetList listDatasetsBySchema(String uri, Integer limit, Integer page, String sort, String direction) {
      StringBuilder sql = new StringBuilder(LIST_SCHEMA_DATASETS);
      // TODO: filtering by fingerprint
      addOrderAndPaging(limit, page, sort, direction, sql);
      Query query = em.createNativeQuery(sql.toString()).setParameter(1, uri);
      markAsSummaryList(query);
      DatasetService.DatasetList list = new DatasetService.DatasetList();
      //noinspection unchecked
      list.datasets = query.getResultList();
      list.total = ((Number) em.createNativeQuery("SELECT COUNT(dataset_id) FROM dataset_schemas WHERE uri = ?1")
            .setParameter(1, uri).getSingleResult()).longValue();
      return list;
   }

   @WithToken
   @WithRoles
   @Override
   public DataSet getDataSet(Integer datasetId) {
      DataSet dataset = DataSet.findById(datasetId);
      if (dataset != null) {
         Hibernate.initialize(dataset.data);
      }
      return dataset;
   }

   private void onLabelChanged(String param) {
      String[] parts = param.split(";");
      if (parts.length != 2) {
         log.errorf("Invalid parameter to onLabelChanged: %s", param);
         return;
      }
      int datasetId = Integer.parseInt(parts[0]);
      int labelId = Integer.parseInt(parts[1]);
      // This is invoked when the label is added/updated. We won't send notifications
      // for that (user can check if there are any changes on his own).
      calculateLabels(datasetId, labelId, true);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   void calculateLabels(int datasetId, int queryLabelId, boolean isRecalculation) {
      log.infof("Calculating labels for dataset %d, label %d", datasetId, queryLabelId);
      // Note: we are fetching even labels that are marked as private/could be otherwise inaccessible
      // to the uploading user. However, the uploader should not have rights to fetch these anyway...
      @SuppressWarnings("unchecked") List<Object[]> extracted =
            (List<Object[]>) em.createNativeQuery(LABEL_QUERY)
                  .setParameter(1, datasetId)
                  .setParameter(2, queryLabelId)
                  .unwrap(NativeQuery.class)
                  .addScalar("label_id", IntegerType.INSTANCE)
                  .addScalar("name", TextType.INSTANCE)
                  .addScalar("function", TextType.INSTANCE)
                  .addScalar("value", JsonNodeBinaryType.INSTANCE)
                  .getResultList();

      Util.evaluateMany(extracted, row -> (String) row[2], row -> (JsonNode) row[3],
            (row, result) -> createLabel(datasetId, (int) row[0], Util.convertToJson(result)),
            row -> createLabel(datasetId, (int) row[0], (JsonNode) row[3]),
            (row, e, jsCode) -> logMessage(datasetId, PersistentLog.ERROR,
                  "Evaluation of label %s failed: '%s' Code:<pre>%s</pre>", row[0], e.getMessage(), jsCode),
            out -> logMessage(datasetId, PersistentLog.DEBUG, "Output while calculating labels: <pre>%s</pre>", out));
      Util.publishLater(tm, eventBus, DataSet.EVENT_LABELS_UPDATED, new DataSet.LabelsUpdatedEvent(datasetId, isRecalculation));
   }

   private void createLabel(int datasetId, int labelId, JsonNode value) {
      Label.Value labelValue = new Label.Value();
      labelValue.datasetId = datasetId;
      labelValue.labelId = labelId;
      labelValue.value = value;
      labelValue.persist();
   }

   void withRecalculationLock(Runnable runnable) {
      recalculationLock.lock();
      try {
         runnable.run();
      } finally {
         recalculationLock.unlock();
      }
   }

   @ConsumeEvent(value = DataSet.EVENT_NEW, blocking = true)
   public void onNewDataset(DataSet.EventNew event) {
      withRecalculationLock(() -> calculateLabels(event.dataset.id, -1, event.isRecalculation));
   }

   private void logMessage(int datasetId, int level, String message, Object... params) {
      String msg = String.format(message, params);
      int testId = (int) em.createNativeQuery("SELECT testid FROM dataset WHERE id = ?1").setParameter(1, datasetId).getSingleResult();
      new DatasetLog(em.getReference(Test.class, testId), em.getReference(DataSet.class, datasetId), level, "labels", msg).persist();
   }
}
