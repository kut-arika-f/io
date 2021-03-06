/**
 * personium.io
 * Copyright 2014 FUJITSU LIMITED
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fujitsu.dc.core.rs.unit;

import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.odata4j.core.OEntityKey;
import org.odata4j.producer.EntityQueryInfo;
import org.odata4j.producer.EntityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fujitsu.dc.common.es.util.IndexNameEncoder;
import com.fujitsu.dc.core.DcCoreConfig;
import com.fujitsu.dc.core.DcCoreException;
import com.fujitsu.dc.core.auth.AccessContext;
import com.fujitsu.dc.core.auth.Privilege;
import com.fujitsu.dc.core.event.EventUtils;
import com.fujitsu.dc.core.model.Box;
import com.fujitsu.dc.core.model.BoxCmp;
import com.fujitsu.dc.core.model.Cell;
import com.fujitsu.dc.core.model.ModelFactory;
import com.fujitsu.dc.core.model.file.BinaryDataAccessException;
import com.fujitsu.dc.core.model.lock.UnitUserLockManager;
import com.fujitsu.dc.core.odata.OEntityWrapper;
import com.fujitsu.dc.core.rs.odata.ODataResource;

/**
 * Jax-RS Resource handling DC Unit Level Api.
 */
public class UnitCtlResource extends ODataResource {
    UriInfo uriInfo;

    static Logger log = LoggerFactory.getLogger(UnitCtlResource.class);

    /**
     * beforeDelete時にCellの検索をした結果をafterDeleteで利用するためのキャッシュ.
     */
    Cell cell;

    /**
     * コンストラクタ.
     * @param accessContext AccessContext
     * @param uriInfo UriInfo
     */
    public UnitCtlResource(AccessContext accessContext, UriInfo uriInfo) {
        super(accessContext, uriInfo.getBaseUri().toASCIIString() + "__ctl/",
                ModelFactory.ODataCtl.unitCtl(accessContext));
        this.uriInfo = uriInfo;
        checkReferenceMode(accessContext);
    }

    private void checkReferenceMode(AccessContext accessContext) {
        String unitUserName = accessContext.getSubject();
        String unitPrefix = DcCoreConfig.getEsUnitPrefix();
        if (unitUserName == null) {
            unitUserName = "anon";
        } else {
            unitUserName = IndexNameEncoder.encodeEsIndexName(unitUserName);
        }
        if (UnitUserLockManager.hasLockObject(unitPrefix + "_" + unitUserName)) {
            throw DcCoreException.Server.SERVICE_MENTENANCE_RESTORE;
        }
    }

    @Override
    public void checkAccessContext(AccessContext ac, Privilege privilege) {

        // ユニットマスター、ユニットユーザ、ユニットローカルユニットユーザなら受け付ける
        if (AccessContext.TYPE_UNIT_MASTER.equals(ac.getType())) {
            return;
        } else if (AccessContext.TYPE_UNIT_USER.equals(ac.getType())) {
            return;
        } else if (AccessContext.TYPE_UNIT_LOCAL.equals(ac.getType())) {
            return;
        } else if (AccessContext.TYPE_INVALID.equals(ac.getType())) {
            ac.throwInvalidTokenException();
        } else if (AccessContext.TYPE_ANONYMOUS.equals(ac.getType())) {
            throw DcCoreException.Auth.AUTHORIZATION_REQUIRED;
        }

        // ユニットマスター、ユニットユーザ、ユニットローカルユニットユーザ以外なら権限エラー
        throw DcCoreException.Auth.UNITUSER_ACCESS_REQUIRED;
    }

    @Override
    public boolean hasPrivilege(AccessContext ac, Privilege privilege) {
        return false;
    }

    @Override
    public void checkSchemaAuth(AccessContext ac) {
    }

    @Override
    public void beforeCreate(final OEntityWrapper oEntityWrapper) {
        if (AccessContext.TYPE_UNIT_USER.equals(this.getAccessContext().getType())
                || AccessContext.TYPE_UNIT_LOCAL.equals(this.getAccessContext().getType())) {
            // ユニットユーザトークンでSubjectの値がある場合はその値にする。
            String subject = this.getAccessContext().getSubject();
            if (subject != null) {
                oEntityWrapper.put("Owner", subject);
            }
        }
    }

    /**
     * 更新時に必要なチェック処理.
     * @param oEntityWrapper OEntityWrapper
     * @param oEntityKey 更新対象のentityKey
     */
    @Override
    public void beforeUpdate(final OEntityWrapper oEntityWrapper, final OEntityKey oEntityKey) {

        String entitySetName = oEntityWrapper.getEntitySet().getName();

        EntityResponse er = this.getODataProducer()
                .getEntity(entitySetName, oEntityKey, new EntityQueryInfo.Builder().build());

        OEntityWrapper oew = (OEntityWrapper) er.getEntity();

        // エンティティごとのアクセス可否判断
        this.checkAccessContextPerEntity(this.getAccessContext(), oew);
    }

    @Override
    public void beforeDelete(final String entitySetName, final OEntityKey oEntityKey) {

        EntityResponse er = this.getODataProducer()
                .getEntity(entitySetName, oEntityKey, new EntityQueryInfo.Builder().build());

        OEntityWrapper oew = (OEntityWrapper) er.getEntity();

        // エンティティごとのアクセス可否判断
        this.checkAccessContextPerEntity(this.getAccessContext(), oew);

        if (Cell.EDM_TYPE_NAME.equals(entitySetName)) {
            String cellId = oew.getUuid();
            cell = ModelFactory.cell(cellId, uriInfo);

            // Cell配下にイベントログが存在する場合は削除
            String owner = cell.getOwner();
            try {
                EventUtils.deleteEventLog(cellId, owner);
            } catch (BinaryDataAccessException e) {
                log.warn("Failed to delete eventlog. CellName=[" + this.cell.getName() + "] owner=[" + owner + "] "
                        + e.getMessage());
            }

            // Cell配下が空っぽじゃなければ409エラー
            if (!cell.isEmpty()) {
                throw DcCoreException.OData.CONFLICT_HAS_RELATED;
            }
        }
    }

    @Override
    public void afterDelete(final String entitySetName, final OEntityKey oEntityKey) {
        if (Cell.EDM_TYPE_NAME.equals(entitySetName)) {
            // デフォルトボックスの削除
            Box box = new Box(this.cell, null);
            BoxCmp boxCmp = ModelFactory.boxCmp(box);
            boxCmp.delete(null);
        }
    }

    /**
     * サービスメタデータリクエストに対応する.
     * @return JAX-RS 応答オブジェクト
     */
    @GET
    @Path("{first: \\$}metadata")
    public Response getMetadata() {
        return super.doGetMetadata();
    }

    /**
     * OPTIONSメソッド.
     * @return JAX-RS Response
     */
    @OPTIONS
    @Path("{first: \\$}metadata")
    public Response optionsMetadata() {
        return super.doGetOptionsMetadata();
    }

    @Override
    public void checkAccessContextPerEntity(AccessContext ac, OEntityWrapper oew) {
        Map<String, Object> meta = oew.getMetadata();
        String owner = (String) meta.get("Owner");

        // マスタートークンだったらチェック不要
        if (AccessContext.TYPE_UNIT_MASTER.equals(ac.getType())) {
            return;
        }

        // ユニットユーザトークン、ユニットローカルユニットユーザトークンではオーナーが同じセルに対してのみ操作を許す.
        // Ownerが空の場合はマスタートークン以外許さない
        if (owner == null || !owner.equals(ac.getSubject())) {
            throw DcCoreException.Auth.NOT_YOURS;
        }
    }

    @Override
    public Privilege getNecessaryReadPrivilege(String entitySetNameStr) {
        // UnitレベルにはPrivilegeを設定できない仕様のためnullを返す。そもそもこの関数呼ぶことはない。
        return null;
    }

    @Override
    public Privilege getNecessaryWritePrivilege(String entitySetNameStr) {
        // UnitレベルにはPrivilegeを設定できない仕様のためnullを返す。そもそもこの関数呼ぶことはない。
        return null;
    }

    @Override
    public Privilege getNecessaryOptionsPrivilege() {
        // UnitレベルにはPrivilegeを設定できない仕様のためnullを返す。そもそもこの関数呼ぶことはない。
        return null;
    }
}
