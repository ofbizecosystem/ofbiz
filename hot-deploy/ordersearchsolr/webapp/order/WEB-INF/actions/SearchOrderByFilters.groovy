/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.math.BigDecimal;
import java.sql.Timestamp;

import javolution.util.FastMap;

import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.util.ClientUtils;

import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilNumber;
import org.ofbiz.base.util.UtilValidate;

import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtil;

import org.ofbiz.order.order.OrderReadHelper;
import org.ofbiz.orders.OrderServices;

int scale = UtilNumber.getBigDecimalScale("order.decimals");
int rounding = UtilNumber.getBigDecimalRoundingMode("order.rounding");
BigDecimal ZERO = (BigDecimal.ZERO).setScale(scale, rounding);

keyword = parameters.keyword?.trim() ?: "";
customer = parameters.customer?.trim() ?: "";
viewSize = Integer.valueOf(context.viewSize ?: 20);
viewIndex = Integer.valueOf(context.viewIndex ?: 0);
channel = parameters.channel;
status = parameters.status;

//Get server object
HttpSolrServer server = new HttpSolrServer(OrderServices.getSolrHost(delegator, "orders"));

// filtered queries on facets.
query = new SolrQuery();
keywordString = keyword.split(" ");
keywordQueryString = "";
keywordString.each { token->
    token = ClientUtils.escapeQueryChars(token);
    if (keywordQueryString) {
        keywordQueryString = keywordQueryString + " OR *" + token + "*";
    } else {
        keywordQueryString = "*" + token + "*";
    }
}
query.setParam("q", "fullText:(" + keywordQueryString + ") AND orderTypeId:SALES_ORDER");
query.setParam("sort", "orderDate desc");

completeUrlParam = "";
if (parameters.keyword) {
    completeUrlParam = "keyword=" + keyword;
}
if (status) {
    queryStringStatusId = "";
    if (completeUrlParam) {
        completeUrlParam = completeUrlParam + "&status=";
    } else {
        completeUrlParam = "status=";
    }
    statusString = status;
    statusIds = statusString.split(":");
    statusIds.each { statusId ->
        if (statusId) {
            if (queryStringStatusId) {
                queryStringStatusId = queryStringStatusId + " OR " + statusId;
            } else {
                queryStringStatusId = statusId;
            }
            completeUrlParam = completeUrlParam + ":" + statusId;
        }
    }
    if (queryStringStatusId) {
        query.addFilterQuery("statusId:(" + queryStringStatusId + ")");
    }
}

if (channel) {
    if (completeUrlParam) {
        completeUrlParam = completeUrlParam + "&channel=" + channel;
    } else {
        completeUrlParam = "channel=" + channel;
    }
    query.addFilterQuery("salesChannelEnumId:" + channel);
}

completeUrlParamForPagination = completeUrlParam;

qryReq = new QueryRequest(query, SolrRequest.METHOD.POST);
rsp = qryReq.process(server);

listSize = Integer.valueOf(rsp.getResults().getNumFound().toString());

Map<String, Object> result = FastMap.newInstance();
if (UtilValidate.isNotEmpty(listSize)) {
    Integer lowIndex = (viewIndex * viewSize) + 1;
    Integer highIndex = (viewIndex + 1) * viewSize;
    if (highIndex > listSize) {
        highIndex = listSize;
    }
    Integer viewIndexLast = (listSize % viewSize) != 0 ? (listSize / viewSize + 1) : (listSize / viewSize);
    result.put("lowIndex", lowIndex);
    result.put("highIndex", highIndex);
    result.put("viewIndexLast", viewIndexLast);
}
paginationValues = result;

query.setRows(viewSize);
query.setStart(paginationValues.get('lowIndex') - 1);
qryReq = new QueryRequest(query, SolrRequest.METHOD.POST);

rsp = qryReq.process(server);
docs = rsp.getResults();
orderIds = [];
docs.each { doc->
    orderIds.add((String) doc.get("orderId"));
}
orderList = delegator.findList("OrderHeader", EntityCondition.makeCondition("orderId", EntityOperator.IN, orderIds), null, ["orderDate DESC"], null, false);
orderInfoList = [];
orderList.each { order->
    orderInfo = [:];
    orderInfo.orderId = order.orderId;
    orderInfo.orderDate = order.orderDate;
    orh = OrderReadHelper.getHelper(order);
    partyId = orh.getPlacingParty()?.partyId;
    orderInfo.partyId = partyId;
    partyEmailResult = dispatcher.runSync("getPartyEmail", [partyId: partyId, userLogin: userLogin]);
    orderInfo.emailAddress = partyEmailResult?.emailAddress;
    channel = order.getRelatedOne("SalesChannelEnumeration");
    orderInfo.channel = channel;
    partyNameResult = dispatcher.runSync("getPartyNameForDate", [partyId: partyId, compareDate: order.orderDate, userLogin: userLogin]);
    orderInfo.customerName = partyNameResult?.fullName;
    List<GenericValue> orderItems = orh.getOrderItems();
       BigDecimal totalItems = ZERO;
       for (GenericValue orderItem : orderItems) {
           totalItems = totalItems.add(OrderReadHelper.getOrderItemQuantity(orderItem)).setScale(scale, rounding);
       }
    orderInfo.orderSize = totalItems.setScale(scale, rounding);
    statusItem = order.getRelatedOne("StatusItem");
    orderInfo.statusId = statusItem.statusId;
    orderInfo.statusDesc = statusItem.description;
    orderInfoList.add(orderInfo);
}
context.completeUrlParam = completeUrlParam;
context.completeUrlParamForPagination = completeUrlParamForPagination;
context.viewIndex = viewIndex;
context.viewSize = viewSize;
context.lowIndex = paginationValues.get("lowIndex");
context.listSize = listSize;
context.orderList = orderList;
context.orderInfoList = orderInfoList;
