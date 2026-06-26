import { Routes, Route, Navigate } from 'react-router-dom';
import { App as AntdApp } from 'antd';
import { lazy, Suspense, useEffect } from 'react';
import MainLayout from './layouts/MainLayout';
import Dashboard from './pages/Dashboard';
import Realtime from './pages/Realtime';
import Sessions from './pages/Sessions';
import People from './pages/People';
import Alerts from './pages/Alerts';
import Login from './pages/Login';
import RequireAuth from './components/RequireAuth';
import PageLoading from './components/PageLoading';
import { bindMessageHandle } from './api/client';

const SessionDetail = lazy(() => import('./pages/SessionDetail'));
const Projects = lazy(() => import('./pages/Projects'));
const Analysis = lazy(() => import('./pages/Analysis'));
const ModelsTools = lazy(() => import('./pages/ModelsTools'));
const SystemSettings = lazy(() => import('./pages/SystemSettings'));

export default function App() {
  const { message } = AntdApp.useApp();
  useEffect(() => {
    bindMessageHandle(message);
  }, [message]);

  return (
    <Routes>
      {/* 登录页：不走 MainLayout，独立全屏 */}
      <Route path="/login" element={<Login />} />

      {/* 后台主体：进入前先过 RequireAuth */}
      <Route
        element={
          <RequireAuth>
            <MainLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/realtime" element={<Realtime />} />
        <Route path="/sessions" element={<Sessions />} />
        <Route path="/sessions/:id" element={<Suspense fallback={<PageLoading />}><SessionDetail /></Suspense>} />
        <Route path="/people" element={<People />} />
        <Route path="/people/:userCode" element={<People />} />
        {/* 旧"我的画像"独立页已合并到员工数据详情面板 */}
        <Route path="/me" element={<Navigate to="/people" replace />} />
        <Route path="/analysis" element={<Suspense fallback={<PageLoading />}><Analysis /></Suspense>} />
        <Route path="/projects" element={<Suspense fallback={<PageLoading />}><Projects /></Suspense>} />
        <Route path="/models-tools" element={<Suspense fallback={<PageLoading />}><ModelsTools /></Suspense>} />
        <Route path="/alerts" element={<Alerts />} />
        <Route path="/system" element={<Suspense fallback={<PageLoading />}><SystemSettings /></Suspense>} />
        <Route path="/cost" element={<Navigate to="/dashboard" replace />} />
        <Route path="/tools" element={<Navigate to="/models-tools" replace />} />
        <Route path="/reports" element={<Navigate to="/analysis" replace />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Route>
    </Routes>
  );
}
