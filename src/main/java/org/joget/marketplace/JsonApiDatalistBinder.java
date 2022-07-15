package org.joget.marketplace;

import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.HttpResponse;
import java.util.Collections;
import java.util.Comparator;
import org.displaytag.tags.TableTagParameters;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.datalist.model.DataList;
import static org.joget.apps.datalist.model.DataList.CHECKBOX_POSITION_BOTH;
import static org.joget.apps.datalist.model.DataList.CHECKBOX_POSITION_LEFT;
import static org.joget.apps.datalist.model.DataList.ORDER_DESCENDING_VALUE;
import org.joget.apps.datalist.model.DataListBinderDefault;
import org.joget.apps.datalist.model.DataListColumn;
import org.joget.apps.datalist.model.DataListCollection;
import org.joget.apps.datalist.model.DataListFilterQueryObject;
import org.joget.commons.util.PagingUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.StringUtil;
import org.joget.plugin.property.service.PropertyUtil;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONObject;

public class JsonApiDatalistBinder extends DataListBinderDefault {
    
    @Override
    public String getClassName() {
        return this.getClass().getName();
    }

    @Override
    public String getName() {
        return "JSON API Data Binder";
    }

    @Override
    public String getVersion() {
        return "7.0.1";
    }

    @Override
    public String getDescription() {
        return "Retrieves data rows from a JSON API, with ability to traverse nested JSON objects.";
    }

    @Override
    public String getLabel() {
        return "JSON API Data Binder";
    }
    
    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/jsonApiDatalistBinder.json", null, true, "messages/jsonApiDatalistBinder");
    }
    
    @Override
    public DataListColumn[] getColumns() {
        Map<String,Object> results = call(null);
        Map<String, DataListColumn> columns = new HashMap<String, DataListColumn>();
        
        if (results != null) {
            String multirowBaseObject = getPropertyString("multirowBaseObject");
            multirowBaseObject = multirowBaseObject.replaceAll("\\[\\d?\\]", "");
            
            Object o = results;
            String prefix = "";
            if (multirowBaseObject.contains(".")) {
                prefix = multirowBaseObject.substring(0, multirowBaseObject.indexOf("."));
                o = results.get(prefix);
            }
            
            recursiveGetColumns(o, columns, prefix, multirowBaseObject, false);
        }
        
        List<DataListColumn> temp = new ArrayList<DataListColumn>(columns.values());
        Collections.sort(temp, new Comparator<DataListColumn>() {
            @Override
            public int compare(DataListColumn a, DataListColumn b)
            {
                return a.getName().compareTo(b.getName());
            }
        });
        
        return temp.toArray(new DataListColumn[0]);
    }
    
    protected void recursiveGetColumns(Object o,  Map<String, DataListColumn> columns, String prefix, String base, boolean baseMatch) {
        //Becomes true if path matches base JSON object name
        if (prefix.equals(base)) {
            prefix = "";
            baseMatch = true;
        }
        
        if (o instanceof Object[]) {
            Object[] array = (Object[]) o;
            if (array.length > 0) {
                //to prevent empty object, loop a few data
                /* Just in case the JSON response has different object keys on multiple rows, 
                    although ideally the response's array keys SHOULD be consistent */
                int max = array.length;  
                if (max > 5) {
                    max = 5;
                }
                for (int i = 0; i < max; i++) {
                    recursiveGetColumns(array[i], columns, prefix, base, baseMatch);
                }
            }
        } else if (o instanceof Map) {
            if (!prefix.isEmpty()) {
                prefix += ".";
            }
            Map m = (Map) o;
            for (Object k : m.keySet()) {
                recursiveGetColumns(m.get(k), columns, prefix + k.toString(), base, baseMatch);
            }
        } else {
            if (baseMatch) {
                columns.put(prefix, new DataListColumn(prefix, prefix, true));
            }
        }
    }
    
    @Override
    public String getPrimaryKeyColumnName() {
        return getPropertyString("primaryKey");
    }
    
    @Override
    public DataListCollection getData(DataList dataList, Map properties, DataListFilterQueryObject[] filterQueryObjects, String sort, Boolean desc, Integer start, Integer rows) {
        DataListCollection resultList = getJsonApiData(dataList);
        
        if ("true".equals(getPropertyString("handlePaging"))) {
            List newResultList = PagingUtils.sortAndPage(resultList, sort, desc, start, rows);
            resultList = new DataListCollection();
            resultList.addAll(newResultList);
        }
        
        return resultList;
    }
    
    protected DataListCollection getJsonApiData(DataList dataList) {
        DataListCollection resultList = new DataListCollection();
        if (!getProperties().containsKey("jsonResultList")) {
            Map<String,Object> results = call(dataList);
        
            if (results != null) {
                String multirowBaseObject = getPropertyString("multirowBaseObject");
                multirowBaseObject = multirowBaseObject.replaceAll("\\[\\d?\\]", "");

                Object o = results;
                String prefix = "";
                if (multirowBaseObject.contains(".")) {
                    prefix = multirowBaseObject.substring(0, multirowBaseObject.indexOf("."));
                    o = results.get(prefix);
                }

                recursiveGetData(o, resultList, new HashMap<String, Object>(), prefix, multirowBaseObject);
            }
            
            setProperty("jsonResultList", resultList);
        } else {
            resultList = (DataListCollection) getProperty("jsonResultList");
        }
        return resultList;
    }
    
    protected void recursiveGetData(Object o, DataListCollection resultList, Map<String, Object> data, String prefix, String base) {
        if (o instanceof Object[]) {
            Object[] array = (Object[]) o;
            if (array.length > 0) {
                for (int i = 0; i < array.length; i++) {
                    recursiveGetData(array[i], resultList, data, prefix, base);
                }
            }
        } else if (o instanceof Map) {
            Map<String, Object> newData = new HashMap<String, Object>();
            newData.putAll(data);
            
            if (prefix.equals(base)) {
                prefix = "";
                resultList.add(newData);
            }
            
            if (!prefix.isEmpty()) {
                prefix += ".";
            }
            
            Object last = null;
            String lastKey = "";
            Map m = (Map) o;
            for (Object k : m.keySet()) {
                if ((prefix + k.toString()).equals(base) || base.startsWith(prefix + k.toString())) {
                    last = m.get(k);
                    lastKey = k.toString();
                } else {
                    recursiveGetData(m.get(k), resultList, newData, prefix + k.toString(), base);
                }
            }
            if (last != null) {
                recursiveGetData(last, resultList, newData, prefix + lastKey, base);
            }
        } else {
            data.put(prefix, o);
        }
    }
    
    @Override
    public int getDataTotalRowCount(DataList dataList, Map properties, DataListFilterQueryObject[] filterQueryObjects) {
        int count = 0;
        
        if (getPropertyString("totalRowCountObject").isEmpty()) {
            count = getJsonApiData(dataList).size();
        } else {
            Map<String,Object> results = call(dataList);
            Object c = getObjectFromMap(getPropertyString("totalRowCountObject"), results);
            
            try {
                count = Integer.parseInt(c.toString());
            } catch (Exception e) {
                LogUtil.error(getClassName(), e, "");
            }
        }
        
        return count;
    }
    
    protected Map<String,Object> call(DataList dataList) {
        Map<String,Object> result = null;
        if (!getProperties().containsKey("jsonResult")) {
            Map<String, String> params = null;
            if (dataList != null) {
                params = getQueryParam(dataList);
            }
            result = callApi(getProperties(), params);
            setProperty("jsonResult", result);
        } else {
            result = (Map<String,Object>) getProperty("jsonResult");
        }
        return result;
    }
    
    protected Map<String, String> getQueryParam(DataList dataList) {
        Map<String, String> param = new HashMap<String, String>();

        String page = dataList.getDataListParamString(TableTagParameters.PARAMETER_PAGE);
        String order = dataList.getDataListParamString(TableTagParameters.PARAMETER_ORDER);
        String sort = dataList.getDataListParamString(TableTagParameters.PARAMETER_SORT);
        
        // determine start and size
        Integer recordSize = dataList.getPageSize();
        Integer start = 0;
        
        if (dataList.getDataListParam(TableTagParameters.PARAMETER_EXPORTTYPE) != null && dataList.getDataListParam(TableTagParameters.PARAMETER_EXPORTING) != null) {
            // exporting, set full list
            page = "1";
            dataList.setPageSize(DataList.MAXIMUM_PAGE_SIZE);
            recordSize = DataList.MAXIMUM_PAGE_SIZE;
        }
        if (page != null && page.trim().length() > 0 && recordSize != null) {
            start = (Integer.parseInt(page) - 1) * recordSize;
        }

        // determine sort column & order direction
        String sortColumn = null;
        Boolean desc = false;
        if (sort != null && !sort.trim().isEmpty()) {
            int sortIndex = Integer.parseInt(sort);

            if (dataList.getCheckboxPosition().equals(CHECKBOX_POSITION_LEFT) || dataList.getCheckboxPosition().equals(CHECKBOX_POSITION_BOTH)) {
                sortIndex = sortIndex - 1;
            }

            DataListColumn[] columns = getColumns();
            if (sortIndex < columns.length) {
                sortColumn = columns[sortIndex].getName();
            }
        } else if (dataList.getDefaultSortColumn() != null && !dataList.getDefaultSortColumn().isEmpty()) {
            sortColumn = dataList.getDefaultSortColumn();
            if (ORDER_DESCENDING_VALUE.equals(dataList.getDefaultOrder())) {
                desc = true;
            }
        }
        if (ORDER_DESCENDING_VALUE.equals(order)) {
            desc = true;
        }

        param.put("sort", sortColumn);
        param.put("desc", Boolean.toString(desc));
        param.put("size", Integer.toString(recordSize));
        param.put("start", Integer.toString(start));

        return param;
    }

    public Map<String, Object> callApi(Map properties, Map<String, String> params) {
        Map<String,Object> result = null;
        String jsonUrl = replaceParam(properties.get("jsonUrl").toString(), params);
        CloseableHttpClient client = null;
        HttpRequestBase request = null;

        try {
            HttpServletRequest httpRequest = WorkflowUtil.getHttpServletRequest();

            HttpClientBuilder httpClientBuilder = HttpClients.custom();
            URL urlObj = new URL(jsonUrl);

            if ("https".equals(urlObj.getProtocol())) {
                SSLContextBuilder builder = new SSLContextBuilder();
                builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
                SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(builder.build(), NoopHostnameVerifier.INSTANCE);
                httpClientBuilder.setSSLSocketFactory(sslsf);
            }

            client = httpClientBuilder.build();

            if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                LogUtil.info(getClass().getName(), ("post".equalsIgnoreCase(properties.get("requestType").toString())?"POST":"GET") + " : " + jsonUrl);
            }

            if ("post".equalsIgnoreCase(properties.get("requestType").toString()) || "put".equalsIgnoreCase(properties.get("requestType").toString())) {
                if ("post".equalsIgnoreCase(properties.get("requestType").toString())) {
                    request = new HttpPost(jsonUrl);
                } else {
                    request = new HttpPut(jsonUrl);
                }

                if ("jsonPayload".equals(properties.get("postMethod").toString())) {
                    JSONObject obj = new JSONObject();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    if (paramsValues != null) {
                        for (Object o : paramsValues) {
                            Map mapping = (HashMap) o;
                            String name  = mapping.get("name").toString();
                            String value = replaceParam(mapping.get("value").toString(), params);
                            obj.accumulate(name, value);
                        }
                    }

                    StringEntity requestEntity = new StringEntity(obj.toString(4), "UTF-8");
                    ((HttpEntityEnclosingRequestBase) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                        LogUtil.info(getClass().getName(), "JSON Payload : " + obj.toString(4));
                    }
                } else if ("custom".equals(properties.get("postMethod"))) {
                    StringEntity requestEntity = new StringEntity(replaceParam(properties.get("customPayload").toString(), params), "UTF-8");
                    ((HttpEntityEnclosingRequestBase) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                        LogUtil.info(getClass().getName(), "Custom JSON Payload : " + properties.get("customPayload").toString());
                    }
                } else {
                    List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    if (paramsValues != null) {
                        for (Object o : paramsValues) {
                            Map mapping = (HashMap) o;
                            String name  = mapping.get("name").toString();
                            String value = replaceParam(mapping.get("value").toString(), params);
                            urlParameters.add(new BasicNameValuePair(name, value));
                            if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                                LogUtil.info(getClass().getName(), "Adding param " + name + " : " + value);
                            }
                        }
                        ((HttpEntityEnclosingRequestBase) request).setEntity(new UrlEncodedFormEntity(urlParameters, "UTF-8"));
                    }
                }
            } else if ("delete".equalsIgnoreCase(properties.get("requestType").toString())) {
                request = new HttpDelete(jsonUrl);
            } else {
                //request = new HttpGet(jsonUrl);
                URI uri = new URI(jsonUrl);
                
                List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
                Object[] paramsValues = (Object[]) properties.get("getParams");
                if (paramsValues != null) {
                    for (Object o : paramsValues) {
                        Map mapping = (HashMap) o;
                        String name  = mapping.get("name").toString();
                        String value = replaceParam(mapping.get("value").toString(), params);
                        
                        if(!value.isEmpty()){
                            uri = appendUri(uri.toString(), name + "=" + value);
                            //urlParameters.add(new BasicNameValuePair(name, value));
                            if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                                LogUtil.info(getClass().getName(), "Adding param " + name + " : " + value);
                            }
                            //((HttpEntityEnclosingRequestBase) request).setEntity(new UrlEncodedFormEntity(urlParameters, "UTF-8"));
                        }
                    }
                }
                jsonUrl = uri.toString();
                request = new HttpGet(jsonUrl);
            }

            Object[] paramsValues = (Object[]) properties.get("headers");
            if (paramsValues != null) {
                for (Object o : paramsValues) {
                    Map mapping = (HashMap) o;
                    String name  = mapping.get("name").toString();
                    String value = replaceParam(mapping.get("value").toString(), params);
                    if (name != null && !name.isEmpty() && value != null && !value.isEmpty()) {
                        request.setHeader(name, value);
                        if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                            LogUtil.info(getClass().getName(), "Adding request header " + name + " : " + value);
                        }
                    }
                }
            }
            if (httpRequest != null) {
                String referer = httpRequest.getHeader("referer");
                if (referer == null || referer.isEmpty()) {
                    referer = httpRequest.getRequestURL().toString();
                }
                request.setHeader("referer", referer);
                if ("true".equalsIgnoreCase(properties.get("copyCookies").toString())) {
                    request.setHeader("Cookie", httpRequest.getHeader("Cookie"));
                }
            }

            HttpResponse response = (HttpResponse) client.execute(request);
            if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                LogUtil.info(getClass().getName(), jsonUrl + " returned with status : " + response.getStatusLine().getStatusCode());
            }

            String jsonResponse = EntityUtils.toString(response.getEntity(), "UTF-8");
            if (jsonResponse != null && !jsonResponse.isEmpty()) {
                if (jsonResponse.startsWith("[") && jsonResponse.endsWith("]")) {
                    jsonResponse = "{ \"response\" : " + jsonResponse + " }";
                }
                if ("true".equalsIgnoreCase(properties.get("debugMode").toString())) {
                    LogUtil.info(getClass().getName(), jsonResponse);
                }
                result = PropertyUtil.getProperties(new JSONObject(jsonResponse));
            }
        } catch (Exception ex) {
            LogUtil.error(getClass().getName(), ex, "");
        } finally {
            try {
                if (request != null) {
                    request.releaseConnection();
                }
                if (client != null) {
                    client.close();
                }
            } catch (IOException ex) {
                LogUtil.error(getClass().getName(), ex, "");
            }
        }
        if (result == null) {
            result = new HashMap<String, Object>();
        }
        
        return result;
    }
    
    public static URI appendUri(String uri, String appendQuery) throws URISyntaxException {
        URI oldUri = new URI(uri);
        return new URI(oldUri.getScheme(), oldUri.getAuthority(), oldUri.getPath(),
                oldUri.getQuery() == null ? appendQuery : oldUri.getQuery() + "&" + appendQuery, oldUri.getFragment());
    }

    public static String replaceParam(String content, Map<String, String> params) {
        if (content != null && !content.isEmpty() && params != null && !params.isEmpty()) {
            for (String s : params.keySet()) {
                String value = params.get(s);
                content = content.replaceAll(StringUtil.escapeRegex("{"+s+"}"), StringUtil.escapeRegex(value != null?value:""));
            }
        }
        return content;
    }
    
    public static Object getObjectFromMap(String key, Map object) {
        if (key.contains(".")) {
            String subKey = key.substring(key.indexOf(".") + 1);
            key = key.substring(0, key.indexOf("."));

            Map tempObject = (Map) getObjectFromMap(key, object);

            if (tempObject != null) {
                return getObjectFromMap(subKey, tempObject);
            }
        } else {
            if (key.contains("[") && key.contains("]")) {
                String tempKey = key.substring(0, key.indexOf("["));
                int number = Integer.parseInt(key.substring(key.indexOf("[") + 1, key.indexOf("]")));
                Object tempObjectArray[] = (Object[]) object.get(tempKey);
                if (tempObjectArray != null && tempObjectArray.length > number) {
                    return tempObjectArray[number];
                }
            } else {
                return object.get(key);
            }
        }
        return null;
    }
}
