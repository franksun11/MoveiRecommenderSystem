package cn.cuit.lsn.service.impl;

import cn.cuit.lsn.dao.RecommendDao;
import cn.cuit.lsn.pojo.RecommenderArgs;
import cn.cuit.lsn.service.RecommenderService;
import cn.cuit.lsn.util.RecommendFactory;
import cn.cuit.lsn.util.RecommendResultEditor;
import cn.cuit.lsn.util.hdfs.HDFSOperation;
import cn.cuit.lsn.util.hive.QuerryHive;
import cn.cuit.lsn.util.hive.SaveToHive;
import cn.cuit.lsn.util.hive.SqoopUtil;
import cn.cuit.lsn.util.thread.DefaultRecommend;
import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.eval.RecommenderBuilder;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveIterator;
import org.apache.mahout.cf.taste.model.DataModel;
import org.apache.mahout.cf.taste.neighborhood.UserNeighborhood;
import org.apache.mahout.cf.taste.similarity.ItemSimilarity;
import org.apache.mahout.cf.taste.similarity.UserSimilarity;
import org.apache.sqoop.client.SqoopClient;
import org.apache.sqoop.model.MLink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Service
public class RecommenderServiceImpl implements RecommenderService {
    //初始推荐结果在HDFS上的存储位置
    private final static String RESULT_HDFS = "output/default_recommends/part-r-00000";
    //将初始推荐结果拷贝到服务器上的位置
    private final static String RESULT_SERVER = "/home/lushuangning/DataFile/";
    //待处理的推荐文件的位置
    private final static String EDITOR_RESULT_SERVER = "/home/lushuangning/DataFile/part-r-00000";
    //处理好的推荐
    private final static String SPLIT_RESULT_SERVER = "/home/lushuangning/DataFile/default_recommends";
    //处理好的推荐存储到HDFS上
    private final static String SPLIT_RESULT_HDFS = "output/resultSplit/default_recommends";

    final static int NEIGHBORHOOD_NUM = 10;
    final static int RECOMMENDER_NUM = 5;
    final static double THRESHOLD = 0.5;
    final static double TRAINING_PERCENTAGE = 0.8;
    final static double EVALUATION_PERCENTAGE = 0.1;
    //FIXME mahout能否直接使用HDFS上的文件作数据模型
    final static String RATINGS_FILE = "/home/lushuangning/DataFile/ratings.csv";

    private static SqoopClient client;

    @Autowired
    private RecommendDao recommendDao;

    class ResultEditorThread extends Thread{
        @Override
        public void run() {
            RecommendResultEditor.resultSplit(new File(EDITOR_RESULT_SERVER)
                    ,new File(SPLIT_RESULT_SERVER));
        }
    }

    @Override
    public void defaultRecommend() {
        //开启单独的线程进行推荐
        DefaultRecommend defaultRecommend = new DefaultRecommend();
        ResultEditorThread resultEditorThread = new ResultEditorThread();


        defaultRecommend.start();
        try {
            defaultRecommend.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        HDFSOperation.copyToLocal(RESULT_HDFS,RESULT_SERVER);

        resultEditorThread.start();
        try {
            resultEditorThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        HDFSOperation.copyFromLocal(SPLIT_RESULT_SERVER,SPLIT_RESULT_HDFS);

        SaveToHive.save(SPLIT_RESULT_HDFS,"default_recommends");

    }

    @Override
    public void customRecommend(RecommenderArgs recommenderArgs) {
        try {
            //构建数据模型
            DataModel dataModel = RecommendFactory.buildDataModel(RATINGS_FILE,",");
            switch (recommenderArgs.getWhichCF()){
                case "UserCF":
                    System.out.println("执行UserCF算法");
                    userCF(dataModel,recommenderArgs);
                    break;
                case "ItemCF":
                    System.out.println("执行ItemCF算法");
                    itemCF(dataModel,recommenderArgs);
                    break;
            }

        } catch (TasteException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void saveToMySQL(String fromDir,String toTable) {
        //对应于终端中的set server --host localhost --port 12000 --weapp sqoop
        String url ="http://localhost:12000/sqoop/";
        client = new SqoopClient(url);
        System.out.println(client);

        MLink mysqlLink = SqoopUtil.createMysqlLink();
        MLink hdfsLink = SqoopUtil.createHdfsLink();
        SqoopUtil.startJob(SqoopUtil.createHdfsToMysqlJob(hdfsLink,mysqlLink,fromDir,toTable));
    }

    @Override
    public List<?> showResult(Integer userId) {
//        List<MovieInfo> movieList =  recommendDao.getRecommendMovie(userId);
        String sql = "select default_recommends.movieid,movies.title,movies.geners\n" +
                "from default_recommends\n" +
                "join movies\n" +
                "on default_recommends.movieid = movies.movieid\n" +
                "where default_recommends.userid = " + userId;
        List<?> movieList = QuerryHive.querry(sql);
        return movieList;
    }

    //基于用户的协同过滤
    public void userCF(DataModel dataModel,RecommenderArgs recommenderArgs)throws TasteException {
        UserSimilarity userSimilarity = RecommendFactory.userSimilarity(RecommendFactory.SIMILARITY.PEARSON, dataModel);
        //用户邻域定义,指定距离最近的N个用户作为邻居
        UserNeighborhood userNeighborhood = RecommendFactory.userNeighborhood(RecommendFactory.NEIGHBORHOOD.NEAREST, userSimilarity, dataModel, recommenderArgs.getNearestNeighbor());
        //创建推荐器,true即包含评分
        RecommenderBuilder recommenderBuilder = RecommendFactory.userRecommender(userSimilarity, userNeighborhood, true);
        //算法评估,计算MAE值,训练80%的数据,测试20%
//        RecommendFactory.evaluate(RecommendFactory.EVALUATOR.AVERAGE_ABSOLUTE_DIFFERENCE, recommenderBuilder, null, dataModel, TRAINING_PERCENTAGE,EVALUATION_PERCENTAGE);
//        RecommendFactory.statsEvaluator(recommenderBuilder, null, dataModel, 30);
        LongPrimitiveIterator iter = dataModel.getUserIDs();
        while (iter.hasNext()) {
            long uid = iter.nextLong();
            List list = recommenderBuilder.buildRecommender(dataModel).recommend(uid, recommenderArgs.getRecommendNum());
            RecommendFactory.showItems(uid, list, true);
        }
    }

    //基于物品的协同过滤
    public void itemCF(DataModel dataModel,RecommenderArgs recommenderArgs) throws TasteException {
        ItemSimilarity itemSimilarity = RecommendFactory.itemSimilarity(RecommendFactory.SIMILARITY.COSINE, dataModel);
        RecommenderBuilder recommenderBuilder = RecommendFactory.itemRecommender(itemSimilarity, true);

        LongPrimitiveIterator iter = dataModel.getUserIDs();
        while (iter.hasNext()) {
            long uid = iter.nextLong();
            List list = recommenderBuilder.buildRecommender(dataModel).recommend(uid, recommenderArgs.getRecommendNum());
            RecommendFactory.showItems(uid, list, true);
        }
    }
}
