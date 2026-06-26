import { Card, Result, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';

/**
 * v2.x 路线图占位页。
 *
 * v2.0 把"成本视角"下线，并新增 4 个洞察类一级菜单（员工数据 / 项目透视 / 模型与工具 / 报告中心）。
 * 这些页面在 v2.0 时仅有信息架构骨架，具体功能在 Phase 2 / Phase 3 逐步填实。
 * 用一个统一的 ComingSoon 组件给到产品全貌感，避免菜单点进去白屏 / 报错。
 *
 * gz
 */
interface ComingSoonProps {
  /** 顶部标题，默认从 props.subTitle 推断；为空时用菜单 H1 接管。 */
  title?: ReactNode;
  /** 副标题：这个页未来会做什么，1~2 句概述。 */
  subTitle: ReactNode;
  /** 期望上线版本：'v2.1' / 'v2.1-Phase2' 等。 */
  milestone: string;
  /** 这个页面要落地的核心指标 / 模块列表（按上线优先级排序）。 */
  highlights: string[];
}

const { Paragraph } = Typography;

export default function ComingSoon({ title, subTitle, milestone, highlights }: ComingSoonProps) {
  return (
    <Card
      styles={{ body: { padding: 0 } }}
      style={{ minHeight: 360, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
    >
      <Result
        status="info"
        icon={<span style={{ fontSize: 56 }}>🛠️</span>}
        title={
          <span style={{ fontSize: 20, fontWeight: 600 }}>
            {title || '即将上线'}
            <Tag color="processing" style={{ marginLeft: 12 }}>{milestone}</Tag>
          </span>
        }
        subTitle={
          <div style={{ maxWidth: 620, margin: '0 auto', textAlign: 'left' }}>
            <Paragraph type="secondary" style={{ marginBottom: 12, textAlign: 'center' }}>
              {subTitle}
            </Paragraph>
            <ul style={{ paddingLeft: 24, color: 'var(--am-ink-3)', lineHeight: 2 }}>
              {highlights.map((h) => (
                <li key={h}>{h}</li>
              ))}
            </ul>
          </div>
        }
      />
    </Card>
  );
}
