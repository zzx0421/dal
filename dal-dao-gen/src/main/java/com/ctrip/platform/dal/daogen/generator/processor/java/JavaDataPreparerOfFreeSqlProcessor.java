package com.ctrip.platform.dal.daogen.generator.processor.java;

import com.ctrip.platform.dal.daogen.CodeGenContext;
import com.ctrip.platform.dal.daogen.Consts;
import com.ctrip.platform.dal.daogen.DalProcessor;
import com.ctrip.platform.dal.daogen.dao.DaoByFreeSql;
import com.ctrip.platform.dal.daogen.entity.ExecuteResult;
import com.ctrip.platform.dal.daogen.entity.GenTaskByFreeSql;
import com.ctrip.platform.dal.daogen.entity.Progress;
import com.ctrip.platform.dal.daogen.generator.java.JavaCodeGenContext;
import com.ctrip.platform.dal.daogen.host.AbstractParameterHost;
import com.ctrip.platform.dal.daogen.host.java.FreeSqlHost;
import com.ctrip.platform.dal.daogen.host.java.JavaGivenSqlResultSetExtractor;
import com.ctrip.platform.dal.daogen.host.java.JavaMethodHost;
import com.ctrip.platform.dal.daogen.host.java.JavaParameterHost;
import com.ctrip.platform.dal.daogen.log.LoggerManager;
import com.ctrip.platform.dal.daogen.utils.DbUtils;
import com.ctrip.platform.dal.daogen.utils.BeanGetter;
import com.ctrip.platform.dal.daogen.utils.SqlBuilder;
import com.ctrip.platform.dal.daogen.utils.TaskUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;

public class JavaDataPreparerOfFreeSqlProcessor extends AbstractJavaDataPreparer implements DalProcessor {
    @Override
    public void process(CodeGenContext context) throws Exception {
        try {
            List<Callable<ExecuteResult>> _freeSqlCallables = prepareFreeSql((CodeGenContext) context);
            TaskUtils.invokeBatch(_freeSqlCallables);
        } catch (Throwable e) {
            LoggerManager.getInstance().error(e);
            throw e;
        }
    }

    private List<Callable<ExecuteResult>> prepareFreeSql(CodeGenContext codeGenCtx) throws SQLException {
        JavaCodeGenContext ctx = (JavaCodeGenContext) codeGenCtx;
        int projectId = ctx.getProjectId();
        final Progress progress = ctx.getProgress();
        final String namespace = ctx.getNamespace();
        final Map<String, JavaMethodHost> _freeSqlPojoHosts = ctx.get_freeSqlPojoHosts();
        final Queue<FreeSqlHost> _freeSqlHosts = ctx.getFreeSqlHosts();
        DaoByFreeSql daoByFreeSql = BeanGetter.getDaoByFreeSql();
        List<GenTaskByFreeSql> freeSqlTasks;
        if (ctx.isRegenerate()) {
            freeSqlTasks = daoByFreeSql.updateAndGetAllTasks(projectId);
            prepareDbFromFreeSql(ctx, freeSqlTasks);
        } else {
            freeSqlTasks = daoByFreeSql.updateAndGetTasks(projectId);
            prepareDbFromFreeSql(ctx, daoByFreeSql.getTasksByProjectId(projectId));
        }

        if (!ctx.isIgnoreApproveStatus() && freeSqlTasks != null && freeSqlTasks.size() > 0) {
            Iterator<GenTaskByFreeSql> ite = freeSqlTasks.iterator();
            while (ite.hasNext()) {
                int approved = ite.next().getApproved();
                if (approved != 2 && approved != 0) {
                    ite.remove();
                }
            }
        }

        // 按照DbName以及ClassName做一次GroupBy(相同DbName的GenTaskByFreeSql作为一组)，且ClassName不区分大小写
        final Map<String, List<GenTaskByFreeSql>> groupBy = freeSqlGroupBy(freeSqlTasks);
        List<Callable<ExecuteResult>> results = new ArrayList<>();
        // 以DbName以及ClassName为维度，为每个维度生成一个DAO类
        for (final Map.Entry<String, List<GenTaskByFreeSql>> entry : groupBy.entrySet()) {
            Callable<ExecuteResult> worker = new Callable<ExecuteResult>() {
                @Override
                public ExecuteResult call() throws Exception {
                    ExecuteResult result = new ExecuteResult("Build  Free SQL[" + entry.getKey() + "] Host");
                    progress.setOtherMessage(result.getTaskName());
                    List<GenTaskByFreeSql> currentTasks = entry.getValue();
                    if (currentTasks.size() < 1)
                        return result;

                    FreeSqlHost host = new FreeSqlHost();
                    host.setDbSetName(currentTasks.get(0).getDatabaseSetName());
                    host.setClassName(currentTasks.get(0).getClass_name());
                    host.setPackageName(namespace);
                    host.setDatabaseCategory(getDatabaseCategory(currentTasks.get(0).getAllInOneName()));

                    List<JavaMethodHost> methods = new ArrayList<>();
                    // 每个Method可能就有一个Pojo
                    for (GenTaskByFreeSql task : currentTasks) {
                        JavaMethodHost method = new JavaMethodHost();
                        method.setSql(task.getSql_content());
                        method.setName(task.getMethod_name());
                        method.setPackageName(namespace);
                        method.setScalarType(task.getScalarType());
                        method.setPojoType(task.getPojoType());
                        method.setPaging(task.getPagination());
                        method.setCrud_type(task.getCrud_type());
                        method.setComments(task.getComment());
                        if (task.getPojo_name() != null && !task.getPojo_name().isEmpty())
                            method.setPojoClassName(WordUtils.capitalize(task.getPojo_name() + "Pojo"));

                        List<JavaParameterHost> params = new ArrayList<>();
                        for (String param : StringUtils.split(task.getParameters(), ";")) {
                            String[] splitedParam = StringUtils.split(param, ",");
                            JavaParameterHost p = new JavaParameterHost();
                            p.setName(splitedParam[0]);
                            p.setSqlType(Integer.valueOf(splitedParam[1]));
                            p.setJavaClass(Consts.jdbcSqlTypeToJavaClass.get(p.getSqlType()));
                            p.setValidationValue(DbUtils.mockATest(p.getSqlType()));
                            boolean sensitive =
                                    splitedParam.length >= 3 ? Boolean.parseBoolean(splitedParam[2]) : false;
                            p.setSensitive(sensitive);
                            params.add(p);
                        }
                        SqlBuilder.rebuildJavaInClauseSQL(task.getSql_content(), params);
                        method.setParameters(params);
                        method.setHints(task.getHints());
                        methods.add(method);

                        if (method.getPojoClassName() != null && !method.getPojoClassName().isEmpty()
                                && !_freeSqlPojoHosts.containsKey(method.getPojoClassName())
                                && !"update".equalsIgnoreCase(method.getCrud_type())) {
                            List<JavaParameterHost> paramHosts = new ArrayList<>();

                            for (AbstractParameterHost _ahost : DbUtils.testAQuerySql(task.getAllInOneName(),
                                    task.getSql_content(), task.getParameters(),
                                    new JavaGivenSqlResultSetExtractor())) {
                                paramHosts.add((JavaParameterHost) _ahost);
                            }

                            method.setFields(paramHosts);
                            _freeSqlPojoHosts.put(method.getPojoClassName(), method);
                        }
                    }
                    host.setMethods(methods);
                    _freeSqlHosts.add(host);
                    result.setSuccessal(true);
                    return result;
                }
            };
            results.add(worker);
        }

        return results;
    }

    private void prepareDbFromFreeSql(CodeGenContext codeGenCtx, List<GenTaskByFreeSql> freeSqls) throws SQLException {
        for (GenTaskByFreeSql task : freeSqls) {
            addDatabaseSet(codeGenCtx, task.getDatabaseSetName());
        }
    }

    /**
     * 按照DbName以及ClassName做一次GroupBy(相同DbName的GenTaskByFreeSql作为一组)， 且ClassName不区分大小写
     *
     * @param tasks
     * @return
     */
    private Map<String, List<GenTaskByFreeSql>> freeSqlGroupBy(List<GenTaskByFreeSql> tasks) {
        Map<String, List<GenTaskByFreeSql>> groupBy = new HashMap<>();

        for (GenTaskByFreeSql task : tasks) {
            String key = String.format("%s_%s", task.getAllInOneName(), task.getClass_name().toLowerCase());
            if (groupBy.containsKey(key)) {
                groupBy.get(key).add(task);
            } else {
                groupBy.put(key, new ArrayList<GenTaskByFreeSql>());
                groupBy.get(key).add(task);
            }
        }
        return groupBy;
    }

}
