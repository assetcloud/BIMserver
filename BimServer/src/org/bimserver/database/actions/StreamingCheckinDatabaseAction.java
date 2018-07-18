package org.bimserver.database.actions;

/******************************************************************************
 * Copyright (C) 2009-2018  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.bimserver.BimServer;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.GenerateGeometryResult;
import org.bimserver.SummaryMap;
import org.bimserver.database.BimserverLockConflictException;
import org.bimserver.database.DatabaseSession;
import org.bimserver.database.OldQuery;
import org.bimserver.database.PostCommitAction;
import org.bimserver.database.Record;
import org.bimserver.database.RecordIterator;
import org.bimserver.database.queries.ConcreteRevisionStackFrame;
import org.bimserver.database.queries.QueryObjectProvider;
import org.bimserver.database.queries.QueryTypeStackFrame;
import org.bimserver.database.queries.om.Include;
import org.bimserver.database.queries.om.Query;
import org.bimserver.database.queries.om.QueryException;
import org.bimserver.database.queries.om.QueryPart;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.geometry.Density;
import org.bimserver.geometry.GeometryGenerationReport;
import org.bimserver.geometry.StreamingGeometryGenerator;
import org.bimserver.mail.MailSystem;
import org.bimserver.models.geometry.Bounds;
import org.bimserver.models.geometry.GeometryFactory;
import org.bimserver.models.geometry.GeometryPackage;
import org.bimserver.models.geometry.Vector3f;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.models.log.NewRevisionAdded;
import org.bimserver.models.store.ConcreteRevision;
import org.bimserver.models.store.DensityCollection;
import org.bimserver.models.store.ExtendedData;
import org.bimserver.models.store.File;
import org.bimserver.models.store.IfcHeader;
import org.bimserver.models.store.NewService;
import org.bimserver.models.store.PluginBundleVersion;
import org.bimserver.models.store.Project;
import org.bimserver.models.store.Revision;
import org.bimserver.models.store.Service;
import org.bimserver.models.store.StoreFactory;
import org.bimserver.models.store.StorePackage;
import org.bimserver.models.store.User;
import org.bimserver.notifications.NewRevisionNotification;
import org.bimserver.plugins.deserializers.ByteProgressReporter;
import org.bimserver.plugins.deserializers.StreamingDeserializer;
import org.bimserver.shared.HashMapVirtualObject;
import org.bimserver.shared.QueryContext;
import org.bimserver.shared.exceptions.ServiceException;
import org.bimserver.shared.exceptions.UserException;
import org.bimserver.webservices.authorization.Authorization;
import org.bimserver.webservices.authorization.ExplicitRightsAuthorization;
import org.bimserver.webservices.impl.RestartableInputStream;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Charsets;

public class StreamingCheckinDatabaseAction extends GenericCheckinDatabaseAction {

	private static final Logger LOGGER = LoggerFactory.getLogger(StreamingCheckinDatabaseAction.class);
	private final String comment;
	private final long poid;
	private final BimServer bimServer;
	private ConcreteRevision concreteRevision;
	private Project project;
	private Authorization authorization;
	private String fileName;
	private long fileSize;
	private InputStream inputStream;
	private StreamingDeserializer deserializer;
	private long newServiceId;
	private Revision newRevision;
	private PackageMetaData packageMetaData;
	private PluginBundleVersion pluginBundleVersion;
	private long topicId;

	public StreamingCheckinDatabaseAction(BimServer bimServer, DatabaseSession databaseSession, AccessMethod accessMethod, long poid, Authorization authorization, String comment, String fileName, InputStream inputStream, StreamingDeserializer deserializer, long fileSize, long newServiceId, PluginBundleVersion pluginBundleVersion, long topicId) {
		super(databaseSession, accessMethod);
		this.bimServer = bimServer;
		this.poid = poid;
		this.authorization = authorization;
		this.comment = comment;
		this.fileName = fileName;
		this.inputStream = inputStream;
		this.deserializer = deserializer;
		this.fileSize = fileSize;
		this.newServiceId = newServiceId;
		this.pluginBundleVersion = pluginBundleVersion;
		this.topicId = topicId;
	}

	public HashMapVirtualObject getByOid(PackageMetaData packageMetaData, DatabaseSession databaseSession, long roid, long oid) throws JsonParseException, JsonMappingException, IOException, QueryException, BimserverDatabaseException {
		Query query = new Query("test", packageMetaData);
		QueryPart queryPart = query.createQueryPart();
		queryPart.addOid(oid);
		QueryObjectProvider queryObjectProvider = new QueryObjectProvider(databaseSession, bimServer, query, Collections.singleton(roid), packageMetaData);
		HashMapVirtualObject first = queryObjectProvider.next();
		return first;
	}
	
	@Override
	public ConcreteRevision execute() throws UserException, BimserverDatabaseException {
		try {
			if (inputStream instanceof RestartableInputStream) {
				((RestartableInputStream)inputStream).restartIfAtEnd();
			}
			
			getDatabaseSession().clearPostCommitActions();
			
			if (fileSize == -1) {
//				setProgress("Deserializing IFC file...", -1);
			} else {
				setProgress("Deserializing IFC file...", 0);
			}
			authorization.canCheckin(poid);
			project = getProjectByPoid(poid);
			int nrConcreteRevisionsBefore = project.getConcreteRevisions().size();
			User user = getUserByUoid(authorization.getUoid());
			if (project == null) {
				throw new UserException("Project with poid " + poid + " not found");
			}
			if (!authorization.hasRightsOnProjectOrSuperProjects(user, project)) {
				throw new UserException("User has no rights to checkin models to this project");
			}
			if (!MailSystem.isValidEmailAddress(user.getUsername())) {
				throw new UserException("Users must have a valid e-mail address to checkin");
			}
//			if (getModel() != null) {
//				checkCheckSum(project);
//			}
			
			packageMetaData = bimServer.getMetaDataManager().getPackageMetaData(project.getSchema());

			// TODO checksum
			// TODO modelcheckers
			// TODO test ifc4

//			long size = 0;
//			if (getModel() != null) {
//				for (IdEObject idEObject : getModel().getValues()) {
//					if (idEObject.eClass().getEAnnotation("hidden") == null) {
//						size++;
//					}
//				}
//				getModel().fixInverseMismatches();
//			}
			
//			for (ModelCheckerInstance modelCheckerInstance : project.getModelCheckers()) {
//				if (modelCheckerInstance.isValid()) {
//					ModelCheckerPlugin modelCheckerPlugin = bimServer.getPluginManager().getModelCheckerPlugin(modelCheckerInstance.getModelCheckerPluginClassName(), true);
//					if (modelCheckerPlugin != null) {
//						ModelChecker modelChecker = modelCheckerPlugin.createModelChecker(null);
//						ModelCheckerResult result = modelChecker.check(getModel(), modelCheckerInstance.getCompiled());
//						if (!result.isValid()) {
//							throw new UserException("Model is not valid according to " + modelCheckerInstance.getName());
//						}
//					}
//				}
//			}
			
			CreateRevisionResult result = createNewConcreteRevision(getDatabaseSession(), -1, project, user, comment.trim());

			newRevision = result.getRevisions().get(0);
			long newRoid = newRevision.getOid();
			QueryContext queryContext = new QueryContext(getDatabaseSession(), packageMetaData, result.getConcreteRevision().getProject().getId(), result.getConcreteRevision().getId(), newRoid, -1); // TODO check
			
			AtomicLong bytesRead = new AtomicLong();
			
			deserializer.setProgressReporter(new ByteProgressReporter() {
				@Override
				public void progress(long byteNumber) {
					bytesRead.set(byteNumber);
					if (fileSize != -1) {
						int perc = (int)(100.0 * byteNumber / fileSize);
						setProgress("Deserializing...", perc);
					}
				}
			});
			
			// This will read the full stream of objects and write to the database directly
			long size = deserializer.read(inputStream, fileName, fileSize, queryContext);
			
			Set<EClass> eClasses = deserializer.getSummaryMap().keySet();
			Map<EClass, Long> startOids = getDatabaseSession().getStartOids();
			if (startOids == null) {
				throw new BimserverDatabaseException("No objects changed");
			}
			Map<EClass, Long> oidCounters = new HashMap<>();
			int s = 0;
			for (EClass eClass : eClasses) {
				if (!DatabaseSession.perRecordVersioning(eClass)) {
					s++;
				}
			}
			ByteBuffer buffer = ByteBuffer.allocate(8 * s);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			for (EClass eClass : eClasses) {
				if (!DatabaseSession.perRecordVersioning(eClass)) {
					long oid = startOids.get(eClass);
					oidCounters.put(eClass, oid);
					buffer.putLong(oid);
				}
			}
			
			queryContext.setOidCounters(oidCounters);
			
			concreteRevision = result.getConcreteRevision();
			concreteRevision.setOidCounters(buffer.array());

			setProgress("Generating inverses/opposites...", -1);
			
			fixInverses(packageMetaData, newRoid);

			ProgressListener progressListener = new ProgressListener() {
				@Override
				public void updateProgress(String state, int percentage) {
					setProgress("Generating geometry...", percentage);
				}
			};
			
			GeometryGenerationReport report = new GeometryGenerationReport();
			report.setOriginalIfcFileName(fileName);
			report.setOriginalIfcFileSize(bytesRead.get());
			report.setNumberOfObjects(size);
			report.setOriginalDeserializer(pluginBundleVersion.getGroupId() + "." + pluginBundleVersion.getArtifactId() + ":" + pluginBundleVersion.getVersion());
			
			StreamingGeometryGenerator geometryGenerator = new StreamingGeometryGenerator(bimServer, progressListener, -1L, report);
			setProgress("Generating geometry...", 0);

			GenerateGeometryResult generateGeometry = geometryGenerator.generateGeometry(getActingUid(), getDatabaseSession(), queryContext);
			
			for (Revision other : concreteRevision.getRevisions()) {
				other.setHasGeometry(true);
			}
			
			concreteRevision.setMultiplierToMm(generateGeometry.getMultiplierToMm());
			concreteRevision.setBounds(generateGeometry.getBounds());
			concreteRevision.setBoundsUntransformed(generateGeometry.getBoundsUntransformed());

			// TODO terrible code, but had to get it going quickly, will cleanup later
			
			for (Revision revision : result.getRevisions()) {
				Bounds newBounds = GeometryFactory.eINSTANCE.createBounds();
				Vector3f min = GeometryFactory.eINSTANCE.createVector3f();
				min.setX(Double.MAX_VALUE);
				min.setY(Double.MAX_VALUE);
				min.setZ(Double.MAX_VALUE);
				Vector3f max = GeometryFactory.eINSTANCE.createVector3f();
				max.setX(-Double.MAX_VALUE);
				max.setY(-Double.MAX_VALUE);
				max.setZ(-Double.MAX_VALUE);
				newBounds.setMin(min);
				newBounds.setMax(max);

				Bounds newBoundsMm = GeometryFactory.eINSTANCE.createBounds();
				Vector3f minMm = GeometryFactory.eINSTANCE.createVector3f();
				minMm.setX(Double.MAX_VALUE);
				minMm.setY(Double.MAX_VALUE);
				minMm.setZ(Double.MAX_VALUE);
				Vector3f maxMm = GeometryFactory.eINSTANCE.createVector3f();
				maxMm.setX(-Double.MAX_VALUE);
				maxMm.setY(-Double.MAX_VALUE);
				maxMm.setZ(-Double.MAX_VALUE);
				newBoundsMm.setMin(minMm);
				newBoundsMm.setMax(maxMm);
				
				Bounds newBoundsu = GeometryFactory.eINSTANCE.createBounds();
				Vector3f minu = GeometryFactory.eINSTANCE.createVector3f();
				minu.setX(Double.MAX_VALUE);
				minu.setY(Double.MAX_VALUE);
				minu.setZ(Double.MAX_VALUE);
				Vector3f maxu = GeometryFactory.eINSTANCE.createVector3f();
				maxu.setX(-Double.MAX_VALUE);
				maxu.setY(-Double.MAX_VALUE);
				maxu.setZ(-Double.MAX_VALUE);
				newBoundsu.setMin(minu);
				newBoundsu.setMax(maxu);

				Bounds newBoundsuMm = GeometryFactory.eINSTANCE.createBounds();
				Vector3f minuMm = GeometryFactory.eINSTANCE.createVector3f();
				minuMm.setX(Double.MAX_VALUE);
				minuMm.setY(Double.MAX_VALUE);
				minuMm.setZ(Double.MAX_VALUE);
				Vector3f maxuMm = GeometryFactory.eINSTANCE.createVector3f();
				maxuMm.setX(-Double.MAX_VALUE);
				maxuMm.setY(-Double.MAX_VALUE);
				maxuMm.setZ(-Double.MAX_VALUE);
				newBoundsuMm.setMin(minuMm);
				newBoundsuMm.setMax(maxuMm);
				
				revision.setBounds(newBounds);
				revision.setBoundsUntransformed(newBoundsu);
				revision.setBoundsMm(newBoundsMm);
				revision.setBoundsUntransformedMm(newBoundsuMm);
				
				for (ConcreteRevision concreteRevision2 : revision.getConcreteRevisions()) {
					Vector3f min2 = concreteRevision2.getBounds().getMin();
					Vector3f max2 = concreteRevision2.getBounds().getMax();

					float mm = concreteRevision2.getMultiplierToMm();
					
					if (min2.getX() < min.getX()) {
						min.setX(min2.getX());
					}
					if (min2.getY() < min.getY()) {
						min.setY(min2.getY());
					}
					if (min2.getZ() < min.getZ()) {
						min.setZ(min2.getZ());
					}
					if (max2.getX() > max.getX()) {
						max.setX(max2.getX());
					}
					if (max2.getY() > max.getY()) {
						max.setY(max2.getY());
					}
					if (max2.getZ() > max.getZ()) {
						max.setZ(max2.getZ());
					}

					if (min2.getX() * mm < minMm.getX()) {
						minMm.setX(min2.getX() * mm);
					}
					if (min2.getY() * mm < minMm.getY()) {
						minMm.setY(min2.getY() * mm);
					}
					if (min2.getZ() * mm < minMm.getZ()) {
						minMm.setZ(min2.getZ() * mm);
					}
					if (max2.getX() * mm > maxMm.getX()) {
						maxMm.setX(max2.getX() * mm);
					}
					if (max2.getY() * mm > maxMm.getY()) {
						maxMm.setY(max2.getY() * mm);
					}
					if (max2.getZ() * mm > maxMm.getZ()) {
						maxMm.setZ(max2.getZ() * mm);
					}
					
					Vector3f min2u = concreteRevision2.getBoundsUntransformed().getMin();
					Vector3f max2u = concreteRevision2.getBoundsUntransformed().getMax();
					if (min2u.getX() < minu.getX()) {
						minu.setX(min2u.getX());
					}
					if (min2u.getY() < minu.getY()) {
						minu.setY(min2u.getY());
					}
					if (min2u.getZ() < minu.getZ()) {
						minu.setZ(min2u.getZ());
					}
					if (max2u.getX() > maxu.getX()) {
						maxu.setX(max2u.getX());
					}
					if (max2u.getY() > maxu.getY()) {
						maxu.setY(max2u.getY());
					}
					if (max2u.getZ() > maxu.getZ()) {
						maxu.setZ(max2u.getZ());
					}
					
					if (min2u.getX() * mm < minuMm.getX()) {
						minuMm.setX(min2u.getX() * mm);
					}
					if (min2u.getY() * mm < minuMm.getY()) {
						minuMm.setY(min2u.getY() * mm);
					}
					if (min2u.getZ() * mm < minuMm.getZ()) {
						minuMm.setZ(min2u.getZ() * mm);
					}
					if (max2u.getX() * mm > maxuMm.getX()) {
						maxuMm.setX(max2u.getX() * mm);
					}
					if (max2u.getY() * mm > maxuMm.getY()) {
						maxuMm.setY(max2u.getY() * mm);
					}
					if (max2u.getZ() * mm> maxuMm.getZ()) {
						maxuMm.setZ(max2u.getZ() * mm);
					}
				}
			}
			
			DensityCollection densityCollection = getDatabaseSession().create(DensityCollection.class);
			concreteRevision.eSet(StorePackage.eINSTANCE.getConcreteRevision_DensityCollection(), densityCollection);
			
			List<org.bimserver.models.store.Density> newList = new ArrayList<>();
			for (Density density : generateGeometry.getDensities()) {
				org.bimserver.models.store.Density dbDensity = StoreFactory.eINSTANCE.createDensity();
				dbDensity.setType(density.getType());
				dbDensity.setDensity(density.getDensityValue());
				dbDensity.setGeometryInfoId(density.getGeometryInfoId());
				dbDensity.setTrianglesBelow(density.getNrPrimitives());
				dbDensity.setVolume(density.getVolume());
				newList.add(dbDensity);
			}
			newList.sort(new Comparator<org.bimserver.models.store.Density>(){
				@Override
				public int compare(org.bimserver.models.store.Density o1, org.bimserver.models.store.Density o2) {
					return Float.compare(o1.getDensity(), o2.getDensity());
				}});
			densityCollection.getDensities().addAll(newList);
			
			// TODO copy code to other 3 places, better deduplicate this code
			
			// For all revisions created, we need to repopulate the density arrays and make sure those are sorted
			for (Revision rev : result.getRevisions()) {
				long nrTriangles = 0;
				densityCollection = getDatabaseSession().create(DensityCollection.class);
				rev.eSet(StorePackage.eINSTANCE.getRevision_DensityCollection(), densityCollection);
				List<org.bimserver.models.store.Density> newList2 = new ArrayList<>();
				for (ConcreteRevision concreteRevision2 : rev.getConcreteRevisions()) {
					for (org.bimserver.models.store.Density density : concreteRevision2.getDensityCollection().getDensities()) {
						newList2.add(density);
						nrTriangles += density.getTrianglesBelow();
					}
				}
				densityCollection.getDensities().clear();
				newList2.sort(new Comparator<org.bimserver.models.store.Density>(){
					@Override
					public int compare(org.bimserver.models.store.Density o1, org.bimserver.models.store.Density o2) {
						return Float.compare(o1.getDensity(), o2.getDensity());
					}});
				densityCollection.getDensities().addAll(newList2);
				rev.eSet(StorePackage.eINSTANCE.getRevision_NrPrimitives(), nrTriangles);
			}

			setProgress("Doing other stuff...", -1);
			
			eClasses = deserializer.getSummaryMap().keySet();
			s = (startOids.containsKey(GeometryPackage.eINSTANCE.getGeometryInfo()) && startOids.containsKey(GeometryPackage.eINSTANCE.getGeometryData())) ? 2 : 0;
			for (EClass eClass : eClasses) {
				if (!DatabaseSession.perRecordVersioning(eClass)) {
					s++;
				}
			}
			buffer = ByteBuffer.allocate(8 * s);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			for (EClass eClass : eClasses) {
				long oid = startOids.get(eClass);
				if (!DatabaseSession.perRecordVersioning(eClass)) {
					buffer.putLong(oid);
				}
			}
			
			if (startOids.containsKey(GeometryPackage.eINSTANCE.getGeometryInfo()) && startOids.containsKey(GeometryPackage.eINSTANCE.getGeometryData())) {
				buffer.putLong(startOids.get(GeometryPackage.eINSTANCE.getGeometryInfo()));
				buffer.putLong(startOids.get(GeometryPackage.eINSTANCE.getGeometryData()));
			}
			
			concreteRevision = result.getConcreteRevision();
			concreteRevision.setOidCounters(buffer.array());
			
			// Clear the cache, we don't want it to cache incomplete oidcounters
			ConcreteRevisionStackFrame.clearCache(concreteRevision.getOid());
			
			result.getConcreteRevision().setSize(size);
			for (Revision revision : result.getRevisions()) {
				revision.setSize((revision.getSize() == null ? 0 : revision.getSize()) + concreteRevision.getSize());
			}
			
			IfcHeader ifcHeader = deserializer.getIfcHeader();
			if (ifcHeader != null) {
				getDatabaseSession().store(ifcHeader);
				concreteRevision.setIfcHeader(ifcHeader);
			}
			project.getConcreteRevisions().add(concreteRevision);
//			if (getModel() != null) {
//				concreteRevision.setChecksum(getModel().getModelMetaData().getChecksum());
//			}
			final NewRevisionAdded newRevisionAdded = getDatabaseSession().create(NewRevisionAdded.class);
			newRevisionAdded.setDate(new Date());
			newRevisionAdded.setExecutor(user);
			final Revision revision = concreteRevision.getRevisions().get(0);
			
			if (newServiceId != -1) {
				NewService newService = getDatabaseSession().get(newServiceId, OldQuery.getDefault());
				revision.getServicesLinked().add(newService);
			}

			concreteRevision.setSummary(new SummaryMap(packageMetaData, deserializer.getSummaryMap()).toRevisionSummary(getDatabaseSession()));

			// If this revision is being created by an external service, store a link to the service in the revision
			if (authorization instanceof ExplicitRightsAuthorization) {
				ExplicitRightsAuthorization explicitRightsAuthorization = (ExplicitRightsAuthorization)authorization;
				if (explicitRightsAuthorization.getSoid() != -1) {
					Service service = getDatabaseSession().get(explicitRightsAuthorization.getSoid(), org.bimserver.database.OldQuery.getDefault());
					revision.setService(service);
				}
			}
			
			newRevisionAdded.setRevision(revision);
			newRevisionAdded.setProject(project);
			newRevisionAdded.setAccessMethod(getAccessMethod());

//			Revision lastRevision = project.getLastRevision();
//			IfcModelInterface ifcModel = null;
//			if (merge && lastRevision != null) {
//				ifcModel = checkinMerge(lastRevision);
//			} else {
//				ifcModel = getModel();
//			}

//			ifcModel.fixOidsFlat(getDatabaseSession());

//			if (bimServer.getServerSettingsCache().getServerSettings().isGenerateGeometryOnCheckin()) {
//				setProgress("Generating Geometry...", -1);
//				new GeometryGenerator(bimServer).generateGeometry(authorization.getUoid(), bimServer.getPluginManager(), getDatabaseSession(), ifcModel, project.getId(), concreteRevision.getId(), true, geometryCache);
//				for (Revision other : concreteRevision.getRevisions()) {
//					other.setHasGeometry(true);
//				}
//			}

			if (nrConcreteRevisionsBefore != 0) {
				// There already was a revision, lets delete it (only when not merging)
				concreteRevision.setClear(true);
			}

			byte[] htmlBytes = report.toHtml().getBytes(Charsets.UTF_8);
			byte[] jsonBytes = report.toJson().toString().getBytes(Charsets.UTF_8);

			storeExtendedData(htmlBytes, "text/html", "html", revision);
			storeExtendedData(jsonBytes, "application/json", "json", revision);
			
			getDatabaseSession().addPostCommitAction(new PostCommitAction() {
				@Override
				public void execute() throws UserException {
					bimServer.getNotificationsManager().notify(new NewRevisionNotification(bimServer, project.getOid(), revision.getOid(), authorization));
					try (DatabaseSession tmpSession = bimServer.getDatabase().createSession()) {
						Project project = tmpSession.get(poid, OldQuery.getDefault());
						project.setCheckinInProgress(0);
						tmpSession.store(project);
						try {
							tmpSession.commit();
						} catch (ServiceException e) {
							LOGGER.error("", e);
						}
					} catch (BimserverDatabaseException e1) {
						LOGGER.error("", e1);
					}
				}
			});

			getDatabaseSession().store(concreteRevision);
			getDatabaseSession().store(project);
		} catch (Throwable e) {
			try (DatabaseSession tmpSession = bimServer.getDatabase().createSession()) {
				Project project = tmpSession.get(poid, OldQuery.getDefault());
				project.setCheckinInProgress(0);
				tmpSession.store(project);
				try {
					tmpSession.commit();
				} catch (ServiceException e2) {
					LOGGER.error("", e2);
				}
			} catch (BimserverDatabaseException e1) {
				LOGGER.error("", e1);
			}
			if (e instanceof BimserverDatabaseException) {
				throw (BimserverDatabaseException) e;
			}
			if (e instanceof UserException) {
				throw (UserException) e;
			}
			throw new UserException(e);
		}
		return concreteRevision;
	}

	private void storeExtendedData(byte[] bytes, String mime, String extension, final Revision revision) throws BimserverDatabaseException {
		ExtendedData extendedData = getDatabaseSession().create(ExtendedData.class);
		File file = getDatabaseSession().create(File.class);
		file.setData(bytes);
		file.setFilename("geometrygenerationreport." + extension);
		file.setMime(mime);
		file.setSize(bytes.length);
		User actingUser = getUserByUoid(authorization.getUoid());
		extendedData.setUser(actingUser);
		extendedData.setTitle("Geometry generation report (" + mime + ")");
		extendedData.setAdded(new Date());
		extendedData.setSize(file.getData().length);
		extendedData.setFile(file);
		revision.getExtendedData().add(extendedData);
		extendedData.setProject(revision.getProject());
		extendedData.setRevision(revision);
		
		getDatabaseSession().store(file);
		getDatabaseSession().store(extendedData);
		
		if (extendedData.getSchema() != null) {
			getDatabaseSession().store(extendedData.getSchema());
		}
	}

	@SuppressWarnings("unchecked")
	private void fixInverses(PackageMetaData packageMetaData, long newRoid) throws QueryException, JsonParseException, JsonMappingException, IOException, BimserverDatabaseException {
		// TODO remove cache, this is essentially a big part of the model in memory again
		Map<Long, HashMapVirtualObject> cache = new HashMap<Long, HashMapVirtualObject>();
		Query query = new Query("Inverses fixer", packageMetaData);
		
		int nrTypes = 0;
		Set<EClass> uniqueTypes = new HashSet<>();
		for (EClass eClass : deserializer.getSummaryMap().keySet()) {
			if (packageMetaData.hasInverses(eClass)) {
				QueryPart queryPart = query.createQueryPart();
				queryPart.addType(eClass, true);
				uniqueTypes.add(eClass);
				nrTypes++;
				for (EReference eReference : packageMetaData.getAllHasInverseReferences(eClass)) {
					Include include = queryPart.createInclude();
					include.addType(eClass, true);
					include.addField(eReference.getName());
				}
			}
		}
		
		QueryObjectProvider queryObjectProvider = new QueryObjectProvider(getDatabaseSession(), bimServer, query, Collections.singleton(newRoid), packageMetaData);
		HashMapVirtualObject next = queryObjectProvider.next();
		EClass lastEClass = null;
		int currentType = 0;
		while (next != null) {
			if (next.eClass() != lastEClass && uniqueTypes.contains(next.eClass()) && queryObjectProvider.getStackFrame() instanceof QueryTypeStackFrame) {
				lastEClass = next.eClass();
				currentType++;
				setProgress("Generating inverses", (100 * currentType / nrTypes));
			}
			if (packageMetaData.hasInverses(next.eClass())) {
				for (EReference eReference : packageMetaData.getAllHasInverseReferences(next.eClass())) {
					Object reference = next.eGet(eReference);
					if (reference != null) {
						if (eReference.isMany()) {
							List<Long> references = (List<Long>)reference;
							for (Long refOid : references) {
								fixInverses(packageMetaData, newRoid, cache, next, eReference, refOid);
							}
						} else {
							fixInverses(packageMetaData, newRoid, cache, next, eReference, (Long)reference);
						}
					}
				}
			}
			next = queryObjectProvider.next();
		}
		
		setProgress("Storing data", -1);
		
		for (HashMapVirtualObject referencedObject : cache.values()) {
			referencedObject.saveOverwrite();
		}
	}

	private void fixInverses(PackageMetaData packageMetaData, long newRoid, Map<Long, HashMapVirtualObject> cache, HashMapVirtualObject next, EReference eReference, long refOid)
			throws JsonParseException, JsonMappingException, IOException, QueryException, BimserverDatabaseException {
		HashMapVirtualObject referencedObject = cache.get(refOid);
		if (referencedObject == null) {
			referencedObject = getByOid(packageMetaData, getDatabaseSession(), newRoid, refOid);
			if (referencedObject == null) {
				throw new BimserverDatabaseException("Referenced object with oid " + refOid + " (" + getDatabaseSession().getEClassForOid(refOid).getName() + ")" + ", referenced from " + next.eClass().getName() + " not found");
			}
			cache.put(refOid, referencedObject);
		}
		EReference oppositeReference = packageMetaData.getInverseOrOpposite(referencedObject.eClass(), eReference);
		if (oppositeReference == null) {
			if (eReference.getName().equals("RelatedElements") && referencedObject.eClass().getName().equals("IfcSpace")) {
				// Ignore, IfcSpace should have  a field called RelatedElements, but it doesn't.
			} else {
//				LOGGER.error("No opposite " + eReference.getName() + " found");
			}
		} else {
			if (oppositeReference.isMany()) {
				Object existingList = referencedObject.eGet(oppositeReference);
				if (existingList != null) {
					int currentSize = ((List<?>)existingList).size();
					referencedObject.setListItemReference(oppositeReference, currentSize, next.eClass(), next.getOid(), 0);
				} else {
					referencedObject.setListItemReference(oppositeReference, 0, next.eClass(), next.getOid(), 0);
				}
			} else {
				referencedObject.setReference(oppositeReference, next.getOid(), 0);
			}
		}
	}

	public String getFileName() {
		return fileName;
	}
	
	public ConcreteRevision getConcreteRevision() {
		return concreteRevision;
	}

	public Revision getRevision() {
		return concreteRevision.getRevisions().get(0);
	}

	public long getCroid() {
		return concreteRevision.getOid();
	}

	public long getActingUid() {
		return authorization.getUoid();
	}

	public long getPoid() {
		return poid;
	}

	public void close() throws IOException {
		inputStream.close();
	}

	public void rollback() throws BimserverDatabaseException {
		// TODO do we need to remove indices too?
		
		LOGGER.info("Rolling back");
		int pid = newRevision.getProject().getId();
		int rid = newRevision.getRid();
		
		Map<EClass, Long> startOids = getDatabaseSession().getStartOids();
		if (startOids == null) {
			throw new BimserverDatabaseException("No objects changed");
		}
		int deleted = 0;
		for (EClass eClass : startOids.keySet()) {
			Long startOid = startOids.get(eClass);
			ByteBuffer mustStartWith = ByteBuffer.wrap(new byte[4]);
			mustStartWith.putInt(pid);
			ByteBuffer startSearchWith = ByteBuffer.wrap(new byte[12]);
			startSearchWith.putInt(pid);
			startSearchWith.putLong(startOid);
			
			String tableName = eClass.getEPackage().getName() + "_" + eClass.getName();
			try {
				if (!getDatabaseSession().getKeyValueStore().isTransactional(getDatabaseSession(), tableName)) {
					// We only need to check the non-transactional tables, the rest is rolled-back by bdb
//					System.out.println("Checking " + tableName);
					try (RecordIterator recordIterator = getDatabaseSession().getKeyValueStore().getRecordIterator(tableName, mustStartWith.array(), startSearchWith.array(), getDatabaseSession())){
						Record record = recordIterator.next();
						while (record != null) {
//							System.out.println("Deleting from " + tableName);
							
							ByteBuffer keyBuffer = ByteBuffer.wrap(record.getKey());
							keyBuffer.getInt(); // pid
							keyBuffer.getLong(); // oid
							int keyRid = -keyBuffer.getInt();
							
							if (keyRid == rid) {
								getDatabaseSession().getKeyValueStore().delete(tableName, record.getKey(), getDatabaseSession());
								deleted++;
							}
							record = recordIterator.next();
						}
					} catch (BimserverLockConflictException e) {
						e.printStackTrace();
					} catch (BimserverDatabaseException e) {
						e.printStackTrace();
					}
				}
			} catch (BimserverDatabaseException e1) {
				e1.printStackTrace();
			}
		}
		LOGGER.info("Deleted " + deleted + " objects in rollback");
//		getDatabaseSession().getKeyValueStore().sync();
	}
}