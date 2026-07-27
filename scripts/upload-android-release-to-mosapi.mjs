#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const DEFAULT_ADMIN_BASE_URL = 'https://mosapi-admin.auditoryworks.co/v1';
const DEFAULT_CHANGELOG = 'No changelog provided.';

function requiredEnv(name) {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function optionalEnv(name, fallback = '') {
  return process.env[name]?.trim() || fallback;
}

function deriveState(version) {
  const override = optionalEnv('MOSAPI_UPDATE_STATE');
  if (override) return override;

  const lower = version.toLowerCase();
  if (lower.includes('alpha')) return 'alpha';
  if (lower.includes('beta')) return 'beta';
  return 'alpha';
}

function readChangelog() {
  const changelogPath = optionalEnv('MOSAPI_CHANGELOG_PATH');
  if (changelogPath && fs.existsSync(changelogPath)) {
    const content = fs.readFileSync(changelogPath, 'utf8').trim();
    if (content) return content;
  }

  return optionalEnv('MOSAPI_CHANGELOG', DEFAULT_CHANGELOG);
}

function appendGithubOutput(name, value) {
  const outputPath = process.env.GITHUB_OUTPUT;
  if (!outputPath) return;

  if (String(value).includes('\n')) {
    const delimiter = `EOF_${Date.now()}_${Math.random().toString(16).slice(2)}`;
    fs.appendFileSync(outputPath, `${name}<<${delimiter}\n${value}\n${delimiter}\n`);
  } else {
    fs.appendFileSync(outputPath, `${name}=${value}\n`);
  }
}

function absoluteUrl(fileUrl, baseUrl) {
  if (!fileUrl) return '';
  if (fileUrl.startsWith('http://') || fileUrl.startsWith('https://')) {
    return fileUrl;
  }
  try {
    return `${new URL(baseUrl).origin}${fileUrl}`;
  } catch {
    return fileUrl;
  }
}

async function responseText(response) {
  const body = await response.text();
  return body.replace(/\s+/g, ' ').trim();
}

function parseJson(value, context) {
  try {
    return JSON.parse(value);
  } catch (error) {
    throw new Error(`${context} returned invalid JSON: ${error.message}. Body: ${value.slice(0, 500)}`);
  }
}

function getFileMd5(filePath) {
  return crypto.createHash('md5').update(fs.readFileSync(filePath)).digest('hex');
}

function getUploadMimeType(filePath) {
  return path.extname(filePath).toLowerCase() === '.apk'
    ? 'application/vnd.android.package-archive'
    : 'application/octet-stream';
}

function getOtaFileType(fileName) {
  // Mosapi's Android update records use type "1" for an APK. Windows
  // artifacts continue to use their filename as the OTA type.
  return path.extname(fileName).toLowerCase() === '.apk' ? '1' : fileName;
}

function findFirstId(value) {
  if (value == null) return null;
  if (Array.isArray(value)) {
    for (const item of value) {
      const id = findFirstId(item);
      if (id != null) return id;
    }
    return null;
  }
  if (typeof value !== 'object') return null;
  if (value.id !== undefined && value.id !== null && String(value.id).trim()) {
    return String(value.id);
  }
  for (const nested of Object.values(value)) {
    const id = findFirstId(nested);
    if (id != null) return id;
  }
  return null;
}

async function uploadFile(filePath, { token, folderId, adminBaseUrl, retries }) {
  const fileName = path.basename(filePath);
  const fileContent = fs.readFileSync(filePath);
  const formData = new FormData();

  formData.append('files', new Blob([fileContent], { type: getUploadMimeType(filePath) }), fileName);
  formData.append('fileInfo', JSON.stringify({ name: fileName, folder: String(folderId) }));

  let lastError;
  for (let attempt = 1; attempt <= retries + 1; attempt += 1) {
    try {
      const response = await fetch(`${adminBaseUrl}/upload`, {
        method: 'POST',
        headers: {
          Authorization: token,
        },
        body: formData,
      });

      const body = await responseText(response);
      if (!response.ok) {
        throw new Error(`Upload failed for ${fileName}. Status: ${response.status}. Response: ${body.slice(0, 1000)}`);
      }

      const parsed = parseJson(body, 'Upload');
      const uploadedId = findFirstId(parsed);
      if (uploadedId == null) {
        throw new Error(`Upload succeeded for ${fileName}, but no file id was returned. Response: ${body.slice(0, 1000)}`);
      }

      const uploaded = Array.isArray(parsed) ? parsed[0] : parsed?.data?.[0] ?? parsed?.data ?? parsed;
      const uploadedAttributes = uploaded?.attributes ?? uploaded?.data?.attributes ?? {};

      return {
        path: filePath,
        name: fileName,
        id: uploadedId,
        url: uploaded.url || uploaded.attributes?.url || uploadedAttributes.url || '',
        md5: getFileMd5(filePath),
      };
    } catch (error) {
      lastError = error;
      console.warn(`Upload attempt ${attempt} failed for ${fileName}: ${error.message}`);
      if (attempt > retries) break;
    }
  }

  throw lastError;
}

async function ensureProductPlatform({ token, adminBaseUrl, productId, expectedPlatform }) {
  if (!expectedPlatform) return;

  const endpoint = `${adminBaseUrl}/products/${productId}`;
  const getResponse = await fetch(endpoint, {
    headers: { Authorization: token },
  });
  const getBody = await responseText(getResponse);
  if (!getResponse.ok) {
    throw new Error(`Failed to inspect Mosapi product ${productId}. Status: ${getResponse.status}. Response: ${getBody.slice(0, 1000)}`);
  }

  const product = parseJson(getBody, 'Fetch product');
  const currentPlatform = product?.data?.attributes?.platform ?? product?.data?.platform ?? '';
  if (currentPlatform === expectedPlatform) {
    console.log(`Mosapi product ${productId} platform is already ${expectedPlatform}.`);
    return;
  }

  console.log(`Correcting Mosapi product ${productId} platform: ${currentPlatform || '<empty>'} -> ${expectedPlatform}.`);
  const updateResponse = await fetch(endpoint, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token,
    },
    body: JSON.stringify({ data: { platform: expectedPlatform } }),
  });
  const updateBody = await responseText(updateResponse);
  if (!updateResponse.ok) {
    throw new Error(`Failed to correct Mosapi product ${productId} platform. Status: ${updateResponse.status}. Response: ${updateBody.slice(0, 1000)}`);
  }

  const updated = parseJson(updateBody, 'Update product');
  const updatedPlatform = updated?.data?.attributes?.platform ?? updated?.data?.platform ?? '';
  if (updatedPlatform !== expectedPlatform) {
    throw new Error(`Mosapi product ${productId} platform remained ${updatedPlatform || '<empty>'} after update.`);
  }
  console.log(`Mosapi product ${productId} platform corrected to ${expectedPlatform}.`);
}

async function createUpdateRecord({
  token,
  adminBaseUrl,
  productId,
  productSlug,
  version,
  changelog,
  state,
  type,
  uploadedFiles,
}) {
  const date = new Date().toISOString();
  const payload = {
    // `productSlug` remains the fixed product identifier used by the app's
    // update query. Mosapi update records require a unique slug, matching the
    // format used by the verified Windows release action.
    slug: `${productSlug}-${version}__${date}`,
    version,
    date,
    state,
    type,
    // The current Mosapi UpdateRequest schema accepts product IDs directly.
    products: [Number.parseInt(productId, 10)],
    descriptions: [
      {
        locale: 'en-us',
        content: changelog,
      },
      {
        locale: 'zh-cn',
        content: changelog,
      },
    ],
    otaFiles: uploadedFiles.map(file => ({
      type: getOtaFileType(file.name),
      file: {
        id: Number(file.id),
      },
      md5: file.md5,
    })),
    imgFiles: [],
    extFiles: [],
  };

  const response = await fetch(`${adminBaseUrl}/updates`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token,
    },
    body: JSON.stringify({ data: payload }),
  });

  const body = await responseText(response);
  if (!response.ok) {
    console.error('Mosapi update payload:', JSON.stringify(payload, null, 2));
    throw new Error(`Failed to create Mosapi update record. Status: ${response.status}. Response: ${body.slice(0, 2000)}`);
  }

  const postResult = parseJson(body, 'Create update');
  const updateId = postResult?.data?.id;
  if (!updateId) {
    throw new Error(`Mosapi update creation succeeded, but no update id was returned. Response: ${body.slice(0, 1000)}`);
  }

  const getResponse = await fetch(`${adminBaseUrl}/updates/${updateId}?populate[otaFiles][populate]=*`, {
    headers: {
      Authorization: token,
    },
  });

  const getBody = await responseText(getResponse);
  if (!getResponse.ok) {
    throw new Error(`Failed to fetch populated Mosapi update record. Status: ${getResponse.status}. Response: ${getBody.slice(0, 1000)}`);
  }

  const getResult = parseJson(getBody, 'Fetch update');
  return {
    id: getResult.data?.id ?? updateId,
    ...(getResult.data?.attributes ?? {}),
  };
}

async function sendWeComNotification({
  webhookKey,
  projectName,
  version,
  uploadedFiles,
  updateRecord,
  changelog,
  adminBaseUrl,
}) {
  if (!webhookKey) return;

  const messageList = [
    `**项目名称**：${projectName}`,
    `**版本号**：${version}`,
  ];

  if (uploadedFiles.length > 0) {
    messageList.push('**发布文件**：');
    uploadedFiles.forEach(file => {
      const url = absoluteUrl(file.url, adminBaseUrl);
      messageList.push(url ? `- ${file.name}: [${url}](${url})` : `- ${file.name}`);
    });
  }

  if (updateRecord?.id) {
    const adminUrl = `${new URL(adminBaseUrl).origin}/admin/content-manager/collectionType/api::update.update/${updateRecord.id}`;
    messageList.push(`**管理后台**：点击前往 Mosapi 查看 [${adminUrl}](${adminUrl})`);
  }

  if (changelog) {
    messageList.push(`\n**📝 本次更新内容**：\n${changelog}`);
  }

  const response = await fetch(`https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=${webhookKey}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      msgtype: 'markdown',
      markdown: {
        content: messageList.join('\n'),
      },
    }),
  });

  if (!response.ok) {
    const body = await responseText(response);
    console.warn(`WeCom notification failed. Status: ${response.status}. Response: ${body.slice(0, 500)}`);
  }
}

async function main() {
  const files = process.argv.slice(2).filter(Boolean).map(file => path.resolve(file));
  if (files.length === 0) {
    throw new Error('At least one APK path must be passed as an argument.');
  }

  files.forEach(file => {
    if (!fs.existsSync(file)) {
      throw new Error(`Artifact does not exist: ${file}`);
    }
    if (!fs.statSync(file).isFile()) {
      throw new Error(`Artifact is not a file: ${file}`);
    }
  });

  const token = requiredEnv('MOSAPI_TOKEN');
  const folderId = requiredEnv('MOSAPI_FOLDER_ID');
  const productId = requiredEnv('MOSAPI_PRODUCT_ID');
  const productSlug = requiredEnv('MOSAPI_PRODUCT_SLUG');
  const productPlatform = optionalEnv('MOSAPI_PRODUCT_PLATFORM');
  const versionValue = requiredEnv('MOSAPI_VERSION');
  const version = versionValue.toLowerCase().startsWith('v') ? versionValue : `v${versionValue}`;
  const adminBaseUrl = optionalEnv('MOSAPI_ADMIN_BASE_URL', DEFAULT_ADMIN_BASE_URL).replace(/\/$/, '');
  const state = deriveState(version);
  const type = optionalEnv('MOSAPI_UPDATE_TYPE', 'optional');
  const changelog = readChangelog();
  const retries = Number(optionalEnv('MOSAPI_UPLOAD_RETRIES', '3'));

  console.log(`Publishing NearCast-Android-TX ${version} to Mosapi.`);
  console.log(`Product: ${productSlug} (${productId}), state=${state}, type=${type}`);
  console.log(`Artifacts: ${files.map(file => path.basename(file)).join(', ')}`);

  await ensureProductPlatform({
    token,
    adminBaseUrl,
    productId,
    expectedPlatform: productPlatform,
  });

  const uploadedFiles = [];
  for (const file of files) {
    console.log(`Uploading ${file}...`);
    const uploaded = await uploadFile(file, { token, folderId, adminBaseUrl, retries });
    uploadedFiles.push(uploaded);
    console.log(`Uploaded ${uploaded.name} -> id=${uploaded.id}`);
  }

  console.log('Creating update record on Mosapi...');
  const updateRecord = await createUpdateRecord({
    token,
    adminBaseUrl,
    productId,
    productSlug,
    version,
    changelog,
    state,
    type,
    uploadedFiles,
  });

  console.log(`Created update record successfully. ID: ${updateRecord.id}, slug=${updateRecord.slug}`);
  appendGithubOutput('uploaded-count', String(uploadedFiles.length));
  appendGithubOutput('uploaded-files', JSON.stringify(uploadedFiles));
  appendGithubOutput('update-id', String(updateRecord.id));
  appendGithubOutput('update-slug', updateRecord.slug ?? '');

  await sendWeComNotification({
    webhookKey: optionalEnv('WECOM_ROBOTS_KEY'),
    projectName: optionalEnv('MOSAPI_PROJECT_NAME', 'NearCast-Android-TX'),
    version,
    uploadedFiles,
    updateRecord,
    changelog,
    adminBaseUrl,
  });
}

main().catch(error => {
  console.error(`Error: ${error.message}`);
  process.exit(1);
});
